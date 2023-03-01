/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.logging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * An {@link ApplicationListener} that configures the {@link LoggingSystem}. If the
 * environment contains a {@code logging.config} property it will be used to bootstrap the
 * logging system, otherwise a default configuration is used. Regardless, logging levels
 * will be customized if the environment contains {@code logging.level.*} entries and
 * logging groups can be defined with {@code logging.group}.
 * <p>
 * Debug and trace logging for Spring, Tomcat, Jetty and Hibernate will be enabled when
 * the environment contains {@code debug} or {@code trace} properties that aren't set to
 * {@code "false"} (i.e. if you start your application using
 * {@literal java -jar myapp.jar [--debug | --trace]}). If you prefer to ignore these
 * properties you can set {@link #setParseArgs(boolean) parseArgs} to {@code false}.
 * <p>
 * By default, log output is only written to the console. If a log file is required the
 * {@code logging.path} and {@code logging.file} properties can be used.
 * <p>
 * Some system properties may be set as side effects, and these can be useful if the
 * logging configuration supports placeholders (i.e. log4j or logback):
 * <ul>
 * <li>{@code LOG_FILE} is set to the value of path of the log file that should be written
 * (if any).</li>
 * <li>{@code PID} is set to the value of the current process ID if it can be determined.
 * </li>
 * </ul>
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Madhura Bhave
 * @since 2.0.0
 * @see LoggingSystem#get(ClassLoader)
 *
 * @tips 实现 GenericApplicationListener 接口，实现根据配置初始化日志系统 Logger 。
 */
public class LoggingApplicationListener implements GenericApplicationListener {

	private static final ConfigurationPropertyName LOGGING_LEVEL = ConfigurationPropertyName.of("logging.level");

	private static final ConfigurationPropertyName LOGGING_GROUP = ConfigurationPropertyName.of("logging.group");

	private static final Bindable<Map<String, String>> STRING_STRING_MAP = Bindable.mapOf(String.class, String.class);

	private static final Bindable<Map<String, String[]>> STRING_STRINGS_MAP = Bindable.mapOf(String.class,
			String[].class);

	/**
	 * The default order for the LoggingApplicationListener.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

	/**
	 * The name of the Spring property that contains a reference to the logging
	 * configuration to load.
	 */
	public static final String CONFIG_PROPERTY = "logging.config";

	/**
	 * The name of the Spring property that controls the registration of a shutdown hook
	 * to shut down the logging system when the JVM exits.
	 * @see LoggingSystem#getShutdownHandler
	 */
	public static final String REGISTER_SHUTDOWN_HOOK_PROPERTY = "logging.register-shutdown-hook";

	/**
	 * The name of the {@link LoggingSystem} bean.
	 */
	public static final String LOGGING_SYSTEM_BEAN_NAME = "springBootLoggingSystem";

	/**
	 * The name of the {@link LogFile} bean.
	 */
	public static final String LOGFILE_BEAN_NAME = "springBootLogFile";

	private static final Map<String, List<String>> DEFAULT_GROUP_LOGGERS;
	static {
		MultiValueMap<String, String> loggers = new LinkedMultiValueMap<>();
		loggers.add("web", "org.springframework.core.codec");
		loggers.add("web", "org.springframework.http");
		loggers.add("web", "org.springframework.web");
		loggers.add("web", "org.springframework.boot.actuate.endpoint.web");
		loggers.add("web", "org.springframework.boot.web.servlet.ServletContextInitializerBeans");
		loggers.add("sql", "org.springframework.jdbc.core");
		loggers.add("sql", "org.hibernate.SQL");
		DEFAULT_GROUP_LOGGERS = Collections.unmodifiableMap(loggers);
	}

	private static final Map<LogLevel, List<String>> LOG_LEVEL_LOGGERS;
	static {
		MultiValueMap<LogLevel, String> loggers = new LinkedMultiValueMap<>();
		loggers.add(LogLevel.DEBUG, "sql");
		loggers.add(LogLevel.DEBUG, "web");
		loggers.add(LogLevel.DEBUG, "org.springframework.boot");
		loggers.add(LogLevel.TRACE, "org.springframework");
		loggers.add(LogLevel.TRACE, "org.apache.tomcat");
		loggers.add(LogLevel.TRACE, "org.apache.catalina");
		loggers.add(LogLevel.TRACE, "org.eclipse.jetty");
		loggers.add(LogLevel.TRACE, "org.hibernate.tool.hbm2ddl");
		LOG_LEVEL_LOGGERS = Collections.unmodifiableMap(loggers);
	}

	private static final Class<?>[] EVENT_TYPES = { ApplicationStartingEvent.class,
			ApplicationEnvironmentPreparedEvent.class, ApplicationPreparedEvent.class, ContextClosedEvent.class,
			ApplicationFailedEvent.class };

	private static final Class<?>[] SOURCE_TYPES = { SpringApplication.class, ApplicationContext.class };

	private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

	private final Log logger = LogFactory.getLog(getClass());

	private LoggingSystem loggingSystem;

	private LogFile logFile;

	private int order = DEFAULT_ORDER;

	private boolean parseArgs = true;

	private LogLevel springBootLogging = null;

	@Override
	public boolean supportsEventType(ResolvableType resolvableType) {
		return isAssignableFrom(resolvableType.getRawClass(), EVENT_TYPES);
	}

	/**
	 * 判断是否是支持的事件来源
	 * @param sourceType
	 * @return
	 */
	@Override
	public boolean supportsSourceType(Class<?> sourceType) {
		return isAssignableFrom(sourceType, SOURCE_TYPES);
	}

	private boolean isAssignableFrom(Class<?> type, Class<?>... supportedTypes) {
		if (type != null) {
			for (Class<?> supportedType : supportedTypes) {
				if (supportedType.isAssignableFrom(type)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		// 在 Spring Boot 应用启动的时候
		if (event instanceof ApplicationStartingEvent) {
			onApplicationStartingEvent((ApplicationStartingEvent) event);
		}
		// 在 Spring Boot 的 Environment 环境准备完成的时候
		else if (event instanceof ApplicationEnvironmentPreparedEvent) {
			onApplicationEnvironmentPreparedEvent((ApplicationEnvironmentPreparedEvent) event);
		}
		// 在 Spring Boot 容器的准备工作已经完成（并未启动）的时候
		else if (event instanceof ApplicationPreparedEvent) {
			onApplicationPreparedEvent((ApplicationPreparedEvent) event);
		}
		// 在 Spring Boot 容器关闭的时候
		else if (event instanceof ContextClosedEvent
				&& ((ContextClosedEvent) event).getApplicationContext().getParent() == null) {
			onContextClosedEvent();
		}
		// 在 Spring Boot 容器启动失败的时候
		else if (event instanceof ApplicationFailedEvent) {
			onApplicationFailedEvent();
		}
	}

	private void onApplicationStartingEvent(ApplicationStartingEvent event) {
		// <1> 创建 LoggingSystem 对象
		this.loggingSystem = LoggingSystem.get(event.getSpringApplication().getClassLoader());
		// <2> LoggingSystem 的初始化的前置处理
		/**
		 * 通过 LoggingSystem 的抽象，对应不同日志框架对应的 LoggingSystem 实现，达到方便透明的接入不同的日志框架~
		 */
		// 执行 LoggingSystem 的初始化的前置处理。
		this.loggingSystem.beforeInitialize();
	}

	private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
		if (this.loggingSystem == null) {
			this.loggingSystem = LoggingSystem.get(event.getSpringApplication().getClassLoader());
		}
		// 初始化
		initialize(event.getEnvironment(), event.getSpringApplication().getClassLoader());
	}

	private void onApplicationPreparedEvent(ApplicationPreparedEvent event) {
		ConfigurableListableBeanFactory beanFactory = event.getApplicationContext().getBeanFactory();
		if (!beanFactory.containsBean(LOGGING_SYSTEM_BEAN_NAME)) {
			// 将创建的 LoggingSystem 对象，注册到 Spring 容器中。
			beanFactory.registerSingleton(LOGGING_SYSTEM_BEAN_NAME, this.loggingSystem);
		}
		if (this.logFile != null && !beanFactory.containsBean(LOGFILE_BEAN_NAME)) {
			beanFactory.registerSingleton(LOGFILE_BEAN_NAME, this.logFile);
		}
	}

	private void onContextClosedEvent() {
		if (this.loggingSystem != null) {
			// 执行清理。
			this.loggingSystem.cleanUp();
		}
	}

	private void onApplicationFailedEvent() {
		if (this.loggingSystem != null) {
			this.loggingSystem.cleanUp();
		}
	}

	/**
	 * Initialize the logging system according to preferences expressed through the
	 * {@link Environment} and the classpath.
	 * @param environment the environment
	 * @param classLoader the classloader
	 */
	protected void initialize(ConfigurableEnvironment environment, ClassLoader classLoader) {
		// <1> 初始化 LoggingSystemProperties 配置
		new LoggingSystemProperties(environment).apply();
		// <2> 初始化 LogFile
		this.logFile = LogFile.get(environment);
		if (this.logFile != null) {
			this.logFile.applyToSystemProperties();
		}
		// <3> 初始化早期的 Spring Boot Logging 级别
		initializeEarlyLoggingLevel(environment);
		// <4> 初始化 LoggingSystem 日志系统
		initializeSystem(environment, this.loggingSystem, this.logFile);
		// <5> 初始化最终的 Spring Boot Logging 级别
		initializeFinalLoggingLevels(environment, this.loggingSystem);
		// <6>
		registerShutdownHookIfNecessary(environment, this.loggingSystem);
	}

	private void initializeEarlyLoggingLevel(ConfigurableEnvironment environment) {
		/**
		 * 可以通过在启动 jar 的时候，跟上 --debug 或 --trace 。
		 * 也可以在配置文件中，添加 debug=true 或 trace=true 。
		 */
		if (this.parseArgs && this.springBootLogging == null) {
			if (isSet(environment, "debug")) {
				this.springBootLogging = LogLevel.DEBUG;
			}
			if (isSet(environment, "trace")) {
				this.springBootLogging = LogLevel.TRACE;
			}
		}
	}

	private boolean isSet(ConfigurableEnvironment environment, String property) {
		String value = environment.getProperty(property);
		return (value != null && !value.equals("false"));
	}

	private void initializeSystem(ConfigurableEnvironment environment, LoggingSystem system, LogFile logFile) {
		// <1> 创建 LoggingInitializationContext 对象
		LoggingInitializationContext initializationContext = new LoggingInitializationContext(environment);
		// <2> 获得日志组件的配置文件
		/**
		 * 一般情况下，我们无需配置。因为根据不同的日志系统，
		 * Spring Boot 按如下“约定规则”组织配置文件名加载日志配置文件：
		 *
		 *
		 日志框架	配置文件
		 Logback	logback-spring.xml, logback-spring.groovy, logback.xml, logback.groovy
		 Log4j		log4j-spring.properties, log4j-spring.xml, log4j.properties, log4j.xml
		 Log4j2		log4j2-spring.xml, log4j2.xml
		 JDK (Java Util Logging)	logging.properties
		 */
		String logConfig = environment.getProperty(CONFIG_PROPERTY);
		// <3> 如果没配置，则直接初始化 LoggingSystem
		if (ignoreLogConfig(logConfig)) {
			system.initialize(initializationContext, null, logFile);
		}
		else {
			// <4> 如果有配置，先尝试加载指定配置文件，然后在初始化 LoggingSystem
			try {
				// 它的作用是，尝试去加载 logConfig 对应的配置文件，看看是否真的存在~
				ResourceUtils.getURL(logConfig).openStream().close();
				system.initialize(initializationContext, logConfig, logFile);
			}
			catch (Exception ex) {
				// NOTE: We can't use the logger here to report the problem
				System.err.println(
						"Logging system failed to initialize " + "using configuration from '" + logConfig + "'");
				ex.printStackTrace(System.err);
				throw new IllegalStateException(ex);
			}
		}
	}

	private boolean ignoreLogConfig(String logConfig) {
		return !StringUtils.hasLength(logConfig) || logConfig.startsWith("-D");
	}

	/**
	 * 初始化最终的 Spring Boot Logging 级别
	 *
	 * @param environment
	 * @param system
	 */
	private void initializeFinalLoggingLevels(ConfigurableEnvironment environment, LoggingSystem system) {
		// <1> 如果 springBootLogging 非空，则设置到日志级别
		if (this.springBootLogging != null) {
			initializeLogLevel(system, this.springBootLogging);
		}
		// <2> 设置 environment 中配置的日志级别
		setLogLevels(system, environment);
	}

	protected void initializeLogLevel(LoggingSystem system, LogLevel level) {
		LOG_LEVEL_LOGGERS.getOrDefault(level, Collections.emptyList())
				.forEach((logger) -> initializeLogLevel(system, level, logger));
	}

	private void initializeLogLevel(LoggingSystem system, LogLevel level, String logger) {
		List<String> groupLoggers = DEFAULT_GROUP_LOGGERS.get(logger);
		if (groupLoggers == null) {
			system.setLogLevel(logger, level);
			return;
		}
		groupLoggers.forEach((groupLogger) -> system.setLogLevel(groupLogger, level));
	}

	protected void setLogLevels(LoggingSystem system, Environment environment) {
		if (!(environment instanceof ConfigurableEnvironment)) {
			return;
		}
		// 创建 Binder 对象
		Binder binder = Binder.get(environment);
		// <1> 获得日志分组的集合
		Map<String, String[]> groups = getGroups();
		binder.bind(LOGGING_GROUP, STRING_STRINGS_MAP.withExistingValue(groups));
		// <2> 获得日志级别的集合
		/**
		 * 在配置文件里添加：
		 *
		 * logging.level.web=INFO
		 * logging.level.xxx.Dog=INFO
		 */
		Map<String, String> levels = binder.bind(LOGGING_LEVEL, STRING_STRING_MAP).orElseGet(Collections::emptyMap);
		// <3> 遍历 levels 集合，逐个设置日志级别
		levels.forEach((name, level) -> {
			String[] groupedNames = groups.get(name);
			if (ObjectUtils.isEmpty(groupedNames)) {
				setLogLevel(system, name, level);
			}
			else {
				setLogLevel(system, groupedNames, level);
			}
		});
	}

	private Map<String, String[]> getGroups() {
		Map<String, String[]> groups = new LinkedHashMap<>();
		DEFAULT_GROUP_LOGGERS.forEach((name, loggers) -> groups.put(name, StringUtils.toStringArray(loggers)));
		return groups;
	}

	private void setLogLevel(LoggingSystem system, String[] names, String level) {
		// 遍历 names 数组
		for (String name : names) {
			setLogLevel(system, name, level);
		}
	}

	private void setLogLevel(LoggingSystem system, String name, String level) {
		try {
			// 获得 loggerName
			name = name.equalsIgnoreCase(LoggingSystem.ROOT_LOGGER_NAME) ? null : name;
			// 设置日志级别
			system.setLogLevel(name, coerceLogLevel(level));
		}
		catch (RuntimeException ex) {
			this.logger.error("Cannot set level '" + level + "' for '" + name + "'");
		}
	}

	private LogLevel coerceLogLevel(String level) {
		String trimmedLevel = level.trim();
		// false => OFF
		if ("false".equalsIgnoreCase(trimmedLevel)) {
			return LogLevel.OFF;
		}
		return LogLevel.valueOf(trimmedLevel.toUpperCase(Locale.ENGLISH));
	}

	//注册 ShutdownHook
	private void registerShutdownHookIfNecessary(Environment environment, LoggingSystem loggingSystem) {
		// 获得 logging.register-shutdown-hook 对应的配置值
		boolean registerShutdownHook = environment.getProperty(REGISTER_SHUTDOWN_HOOK_PROPERTY, Boolean.class, false);
		// 如果开启
		if (registerShutdownHook) {
			// <x> 获得 shutdownHandler 钩子
			Runnable shutdownHandler = loggingSystem.getShutdownHandler();
			// 注册 ShutdownHooK
			if (shutdownHandler != null && shutdownHookRegistered.compareAndSet(false, true)) {
				registerShutdownHook(new Thread(shutdownHandler));
			}
		}
	}

	void registerShutdownHook(Thread shutdownHook) {
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Sets a custom logging level to be used for Spring Boot and related libraries.
	 * @param springBootLogging the logging level
	 */
	public void setSpringBootLogging(LogLevel springBootLogging) {
		this.springBootLogging = springBootLogging;
	}

	/**
	 * Sets if initialization arguments should be parsed for {@literal debug} and
	 * {@literal trace} properties (usually defined from {@literal --debug} or
	 * {@literal --trace} command line args). Defaults to {@code true}.
	 * @param parseArgs if arguments should be parsed
	 */
	public void setParseArgs(boolean parseArgs) {
		this.parseArgs = parseArgs;
	}

}
