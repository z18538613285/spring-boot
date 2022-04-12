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

package org.springframework.boot.web.servlet.context;

import java.util.Collection;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.Scope;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.context.support.ServletContextAwareProcessor;
import org.springframework.web.context.support.ServletContextResource;
import org.springframework.web.context.support.ServletContextScope;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * A {@link WebApplicationContext} that can be used to bootstrap itself from a contained
 * {@link ServletWebServerFactory} bean.
 * <p>
 * This context will create, initialize and run an {@link WebServer} by searching for a
 * single {@link ServletWebServerFactory} bean within the {@link ApplicationContext}
 * itself. The {@link ServletWebServerFactory} is free to use standard Spring concepts
 * (such as dependency injection, lifecycle callbacks and property placeholder variables).
 * <p>
 * In addition, any {@link Servlet} or {@link Filter} beans defined in the context will be
 * automatically registered with the web server. In the case of a single Servlet bean, the
 * '/' mapping will be used. If multiple Servlet beans are found then the lowercase bean
 * name will be used as a mapping prefix. Any Servlet named 'dispatcherServlet' will
 * always be mapped to '/'. Filter beans will be mapped to all URLs ('/*').
 * <p>
 * For more advanced configuration, the context can instead define beans that implement
 * the {@link ServletContextInitializer} interface (most often
 * {@link ServletRegistrationBean}s and/or {@link FilterRegistrationBean}s). To prevent
 * double registration, the use of {@link ServletContextInitializer} beans will disable
 * automatic Servlet and Filter bean registration.
 * <p>
 * Although this context can be used directly, most developers should consider using the
 * {@link AnnotationConfigServletWebServerApplicationContext} or
 * {@link XmlServletWebServerApplicationContext} variants.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @since 2.0.0
 * @see AnnotationConfigServletWebServerApplicationContext
 * @see XmlServletWebServerApplicationContext
 * @see ServletWebServerFactory
 *
 * @tips 实现 ConfigurableWebServerApplicationContext 接口，继承 GenericWebApplicationContext 类，
 * Spring Boot 使用 Servlet Web 服务器的 ApplicationContext 实现类。
 *
 * org.springframework.web.context.support.GenericWebApplicationContext ，是 Spring Framework 提供的类，就不细看啦。
 */
public class ServletWebServerApplicationContext extends GenericWebApplicationContext
		implements ConfigurableWebServerApplicationContext {

	private static final Log logger = LogFactory.getLog(ServletWebServerApplicationContext.class);

	/**
	 * Constant value for the DispatcherServlet bean name. A Servlet bean with this name
	 * is deemed to be the "main" servlet and is automatically given a mapping of "/" by
	 * default. To change the default behavior you can use a
	 * {@link ServletRegistrationBean} or a different bean name.
	 */
	public static final String DISPATCHER_SERVLET_NAME = "dispatcherServlet";

	private volatile WebServer webServer;

	private ServletConfig servletConfig;

	private String serverNamespace;

	/**
	 * Create a new {@link ServletWebServerApplicationContext}.
	 */
	public ServletWebServerApplicationContext() {
	}

	/**
	 * Create a new {@link ServletWebServerApplicationContext} with the given
	 * {@code DefaultListableBeanFactory}.
	 * @param beanFactory the DefaultListableBeanFactory instance to use for this context
	 */
	public ServletWebServerApplicationContext(DefaultListableBeanFactory beanFactory) {
		super(beanFactory);
	}

	/**
	 * Register ServletContextAwareProcessor.
	 * @see ServletContextAwareProcessor
	 *
	 * @tips 覆写 #postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) 方法，
	 */
	@Override
	protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// <1.1> 注册 WebApplicationContextServletContextAwareProcessor
		/**
		 * WebApplicationContextServletContextAwareProcessor 的作用，主要是处理实现 ServletContextAware 接口的 Bean 。
		 * 在这个处理类，初始化这个 Bean 中的 ServletContext 属性，这样在实现 ServletContextAware 接口的 Bean 中就可以拿到 ServletContext 对象了，
		 * Spring 中 Aware 接口就是这样实现的。
		 */
		beanFactory.addBeanPostProcessor(new WebApplicationContextServletContextAwareProcessor(this));
		// <1.2> 忽略 ServletContextAware 接口。忽略 ServletContextAware 接口，
		// 因为实现 ServletContextAware 接口的 Bean 在 <1.1> 中的 WebApplicationContextServletContextAwareProcessor 中已经处理了。
		beanFactory.ignoreDependencyInterface(ServletContextAware.class);
		// <2> 注册 ExistingWebApplicationScopes
		registerWebApplicationScopes();
	}

	/**
	 * 覆写 #refresh() 方法，初始化 Spring 容器。
	 * @throws BeansException
	 * @throws IllegalStateException
	 */
	@Override
	public final void refresh() throws BeansException, IllegalStateException {
		try {
			super.refresh();
		}
		catch (RuntimeException ex) {
			// <X> 如果发生异常，停止 WebServer
			stopAndReleaseWebServer();
			throw ex;
		}
	}

	/**
	 * 覆写 #onRefresh() 方法，在容器初始化时，完成 WebServer 的创建（不包括启动）。
	 */
	@Override
	protected void onRefresh() {
		// <1> 调用父方法
		super.onRefresh();
		try {
			// 创建 WebServer
			createWebServer();
		}
		catch (Throwable ex) {
			throw new ApplicationContextException("Unable to start web server", ex);
		}
	}

	/**
	 * 覆写 #finishRefresh() 方法，在容器初始化完成时，启动 WebServer 。
	 */
	@Override
	protected void finishRefresh() {
		// <1> 调用父方法
		super.finishRefresh();
		// <2> 启动 WebServer
		WebServer webServer = startWebServer();
		// <3> 如果创建 WebServer 成功，发布 ServletWebServerInitializedEvent 事件
		if (webServer != null) {
			publishEvent(new ServletWebServerInitializedEvent(webServer, this));
		}
	}

	/**
	 * 覆写 #onClose() 方法，在 Spring 容器被关闭时，关闭 WebServer 。
	 */
	@Override
	protected void onClose() {
		// 调用父方法
		super.onClose();
		// 停止 WebServer
		stopAndReleaseWebServer();
	}

	private void createWebServer() {
		WebServer webServer = this.webServer;
		ServletContext servletContext = getServletContext();
		// <1> 如果 webServer 为空，说明未初始化
		if (webServer == null && servletContext == null) {
			// <1.1> 获得 ServletWebServerFactory 对象
			ServletWebServerFactory factory = getWebServerFactory();
			// <1.2> 获得 ServletContextInitializer 对象
			// <1.3> 创建（获得） WebServer 对象
			this.webServer = factory.getWebServer(getSelfInitializer());
		}
		else if (servletContext != null) {
			try {
				getSelfInitializer().onStartup(servletContext);
			}
			catch (ServletException ex) {
				throw new ApplicationContextException("Cannot initialize servlet context", ex);
			}
		}
		// <3> 初始化 PropertySource
		initPropertySources();
	}

	/**
	 * Returns the {@link ServletWebServerFactory} that should be used to create the
	 * embedded {@link WebServer}. By default this method searches for a suitable bean in
	 * the context itself.
	 * @return a {@link ServletWebServerFactory} (never {@code null})
	 *
	 * @tips 默认情况下，此处返回的会是 org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory 对象。
	 * 在我们引入 spring-boot-starter-web 依赖时，默认会引入 spring-boot-starter-tomcat 依赖。
	 * 此时，org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryConfiguration 在自动配置时，
	 * 会配置出 TomcatServletWebServerFactory Bean 对象。因此，此时会获得 TomcatServletWebServerFactory 对象。
	 */
	protected ServletWebServerFactory getWebServerFactory() {
		// Use bean names so that we don't consider the hierarchy
		// 获得 ServletWebServerFactory 类型对应的 Bean 的名字们
		String[] beanNames = getBeanFactory().getBeanNamesForType(ServletWebServerFactory.class);
		// 如果是 0 个，抛出 ApplicationContextException 异常，因为至少要一个
		if (beanNames.length == 0) {
			throw new ApplicationContextException("Unable to start ServletWebServerApplicationContext due to missing "
					+ "ServletWebServerFactory bean.");
		}
		// 如果是 > 1 个，抛出 ApplicationContextException 异常，因为不知道初始化哪个
		if (beanNames.length > 1) {
			throw new ApplicationContextException("Unable to start ServletWebServerApplicationContext due to multiple "
					+ "ServletWebServerFactory beans : " + StringUtils.arrayToCommaDelimitedString(beanNames));
		}
		// 获得 ServletWebServerFactory 类型对应的 Bean 对象
		return getBeanFactory().getBean(beanNames[0], ServletWebServerFactory.class);
	}

	/**
	 * Returns the {@link ServletContextInitializer} that will be used to complete the
	 * setup of this {@link WebApplicationContext}.
	 * @return the self initializer
	 * @see #prepareWebApplicationContext(ServletContext)
	 */
	private org.springframework.boot.web.servlet.ServletContextInitializer getSelfInitializer() {
		return this::selfInitialize;
	}

	/**
	 * 获得 ServletContextInitializer 对象。
	 * @param servletContext
	 * @throws ServletException
	 */
	private void selfInitialize(ServletContext servletContext) throws ServletException {
		// <1> 添加 Spring 容器到 servletContext 属性中。
		prepareWebApplicationContext(servletContext);
		// <2> 注册 ServletContextScope
		registerApplicationScope(servletContext);
		// <3> 注册 web-specific environment beans ("contextParameters", "contextAttributes")，这样，从 BeanFactory 中，也可以获得到 servletContext 。
		WebApplicationContextUtils.registerEnvironmentBeans(getBeanFactory(), servletContext);
		// <4> 获得所有 ServletContextInitializer ，并逐个进行启动
		for (ServletContextInitializer beans : getServletContextInitializerBeans()) {
			beans.onStartup(servletContext);
		}
		//至此，内嵌的 Servlet Web 服务器，已经能够被请求了。
	}

	private void registerApplicationScope(ServletContext servletContext) {
		ServletContextScope appScope = new ServletContextScope(servletContext);
		getBeanFactory().registerScope(WebApplicationContext.SCOPE_APPLICATION, appScope);
		// Register as ServletContext attribute, for ContextCleanupListener to detect it.
		servletContext.setAttribute(ServletContextScope.class.getName(), appScope);
	}

	private void registerWebApplicationScopes() {
		// 创建 ExistingWebApplicationScopes 对象
		ExistingWebApplicationScopes existingScopes = new ExistingWebApplicationScopes(getBeanFactory());
		// 注册 ExistingWebApplicationScopes 到 WebApplicationContext 中
		WebApplicationContextUtils.registerWebApplicationScopes(getBeanFactory());
		// 恢复
		existingScopes.restore();
	}

	/**
	 * Returns {@link ServletContextInitializer}s that should be used with the embedded
	 * web server. By default this method will first attempt to find
	 * {@link ServletContextInitializer}, {@link Servlet}, {@link Filter} and certain
	 * {@link EventListener} beans.
	 * @return the servlet initializer beans
	 */
	protected Collection<ServletContextInitializer> getServletContextInitializerBeans() {
		return new ServletContextInitializerBeans(getBeanFactory());
	}

	/**
	 * Prepare the {@link WebApplicationContext} with the given fully loaded
	 * {@link ServletContext}. This method is usually called from
	 * {@link ServletContextInitializer#onStartup(ServletContext)} and is similar to the
	 * functionality usually provided by a {@link ContextLoaderListener}.
	 * @param servletContext the operational servlet context
	 */
	protected void prepareWebApplicationContext(ServletContext servletContext) {
		// 如果已经在 ServletContext 中，则根据情况进行判断。
		Object rootContext = servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
		if (rootContext != null) {
			// 如果是相同容器，抛出 IllegalStateException 异常。说明可能有重复的 ServletContextInitializers 。
			if (rootContext == this) {
				throw new IllegalStateException(
						"Cannot initialize context because there is already a root application context present - "
								+ "check whether you have multiple ServletContextInitializers!");
			}
			// 如果不同容器，则直接返回。
			return;
		}
		Log logger = LogFactory.getLog(ContextLoader.class);
		servletContext.log("Initializing Spring embedded WebApplicationContext");
		try {
			// <X> 设置当前 Spring 容器到 ServletContext 中。从 servletContext 的属性种，可以拿到其拥有的 Spring 容器。
			servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, this);
			// 打印日志
			if (logger.isDebugEnabled()) {
				logger.debug("Published root WebApplicationContext as ServletContext attribute with name ["
						+ WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE + "]");
			}
			// <Y> 设置到 `servletContext` 属性中。Spring 容器的 servletContext 属性，可以拿到 ServletContext 对象。
			setServletContext(servletContext);
			// 打印日志
			if (logger.isInfoEnabled()) {
				long elapsedTime = System.currentTimeMillis() - getStartupDate();
				logger.info("Root WebApplicationContext: initialization completed in " + elapsedTime + " ms");
			}
		}
		catch (RuntimeException | Error ex) {
			logger.error("Context initialization failed", ex);
			servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, ex);
			throw ex;
		}
	}

	/**
	 * 启动 WebServer 。
	 * @return
	 */
	private WebServer startWebServer() {
		WebServer webServer = this.webServer;
		if (webServer != null) {
			webServer.start();
		}
		return webServer;
	}

	private void stopAndReleaseWebServer() {
		// 获得 WebServer 对象，避免被多线程修改了
		WebServer webServer = this.webServer;
		if (webServer != null) {
			try {
				// 停止 WebServer 对象
				webServer.stop();
				// 置空 webServer
				this.webServer = null;
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	@Override
	protected Resource getResourceByPath(String path) {
		if (getServletContext() == null) {
			return new ClassPathContextResource(path, getClassLoader());
		}
		return new ServletContextResource(getServletContext(), path);
	}

	@Override
	public String getServerNamespace() {
		return this.serverNamespace;
	}

	@Override
	public void setServerNamespace(String serverNamespace) {
		this.serverNamespace = serverNamespace;
	}

	@Override
	public void setServletConfig(ServletConfig servletConfig) {
		this.servletConfig = servletConfig;
	}

	@Override
	public ServletConfig getServletConfig() {
		return this.servletConfig;
	}

	/**
	 * Returns the {@link WebServer} that was created by the context or {@code null} if
	 * the server has not yet been created.
	 * @return the embedded web server
	 */
	@Override
	public WebServer getWebServer() {
		return this.webServer;
	}

	/**
	 * Utility class to store and restore any user defined scopes. This allow scopes to be
	 * registered in an ApplicationContextInitializer in the same way as they would in a
	 * classic non-embedded web application context.
	 */
	public static class ExistingWebApplicationScopes {

		private static final Set<String> SCOPES;

		static {
			Set<String> scopes = new LinkedHashSet<>();
			scopes.add(WebApplicationContext.SCOPE_REQUEST);
			scopes.add(WebApplicationContext.SCOPE_SESSION);
			SCOPES = Collections.unmodifiableSet(scopes);
		}

		private final ConfigurableListableBeanFactory beanFactory;

		private final Map<String, Scope> scopes = new HashMap<>();

		public ExistingWebApplicationScopes(ConfigurableListableBeanFactory beanFactory) {
			this.beanFactory = beanFactory;
			for (String scopeName : SCOPES) {
				Scope scope = beanFactory.getRegisteredScope(scopeName);
				if (scope != null) {
					this.scopes.put(scopeName, scope);
				}
			}
		}

		public void restore() {
			this.scopes.forEach((key, value) -> {
				if (logger.isInfoEnabled()) {
					logger.info("Restoring user defined scope " + key);
				}
				this.beanFactory.registerScope(key, value);
			});
		}

	}

}
