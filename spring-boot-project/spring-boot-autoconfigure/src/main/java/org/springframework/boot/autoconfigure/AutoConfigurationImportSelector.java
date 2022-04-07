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

package org.springframework.boot.autoconfigure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link DeferredImportSelector} to handle {@link EnableAutoConfiguration
 * auto-configuration}. This class can also be subclassed if a custom variant of
 * {@link EnableAutoConfiguration @EnableAutoConfiguration} is needed.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @since 1.3.0
 * @see EnableAutoConfiguration
 *
 * @tips 处理@EnableAutoConfiguration注解的资源导入。
 * AutoConfigurationGroup ，是 AutoConfigurationImportSelector 的内部实现类，
 * 实现 DeferredImportSelector.Group、BeanClassLoaderAware、BeanFactoryAware、ResourceLoaderAware 接口，自动配置的 Group 实现类。
 */
public class AutoConfigurationImportSelector implements DeferredImportSelector, BeanClassLoaderAware,
		ResourceLoaderAware, BeanFactoryAware, EnvironmentAware, Ordered {

	private static final AutoConfigurationEntry EMPTY_ENTRY = new AutoConfigurationEntry();

	private static final String[] NO_IMPORTS = {};

	private static final Log logger = LogFactory.getLog(AutoConfigurationImportSelector.class);

	private static final String PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

	private ConfigurableListableBeanFactory beanFactory;

	private Environment environment;

	private ClassLoader beanClassLoader;

	private ResourceLoader resourceLoader;

	@Override
	public String[] selectImports(AnnotationMetadata annotationMetadata) {
		if (!isEnabled(annotationMetadata)) {
			return NO_IMPORTS;
		}
		AutoConfigurationMetadata autoConfigurationMetadata = AutoConfigurationMetadataLoader
				.loadMetadata(this.beanClassLoader);
		AutoConfigurationEntry autoConfigurationEntry = getAutoConfigurationEntry(autoConfigurationMetadata,
				annotationMetadata);
		return StringUtils.toStringArray(autoConfigurationEntry.getConfigurations());
	}

	/**
	 * Return the {@link AutoConfigurationEntry} based on the {@link AnnotationMetadata}
	 * of the importing {@link Configuration @Configuration} class.
	 * @param autoConfigurationMetadata the auto-configuration metadata
	 * @param annotationMetadata the annotation metadata of the configuration class
	 * @return the auto-configurations that should be imported
	 */
	protected AutoConfigurationEntry getAutoConfigurationEntry(AutoConfigurationMetadata autoConfigurationMetadata,
			AnnotationMetadata annotationMetadata) {
		//<1> 判断开启。如未开启，返回是否为空。
		if (!isEnabled(annotationMetadata)) {
			return EMPTY_ENTRY;
		}
		// <2> 获得注解的属性
		AnnotationAttributes attributes = getAttributes(annotationMetadata);
		// <3> 获得符合条件的配置类的    目录
		List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
		// <3.1> 移除重复的配置类
		configurations = removeDuplicates(configurations);
		// <4> 获得需要解决的配置类
		Set<String> exclusions = getExclusions(annotationMetadata, attributes);
		// <4.1> 校验的配置类是否可行
		checkExcludedClasses(configurations, exclusions);
		// <4.2> 从配置中，移除需要排除的配置类
		configurations.removeAll(exclusions);
		// <5>根据条件（Condition），过滤掉不符合条件的配置类
		configurations = filter(configurations, autoConfigurationMetadata);
		// <6> 触发自动配置类引入完成的事件
		fireAutoConfigurationImportEvents(configurations, exclusions);
		// <7> 创建 AutoConfigurationEntry 对象
		return new AutoConfigurationEntry(configurations, exclusions);
	}

	@Override
	public Class<? extends Group> getImportGroup() {
		return AutoConfigurationGroup.class;
	}


	protected boolean isEnabled(AnnotationMetadata metadata) {
		// 判断 "spring.boot.enableautoconfiguration" 配置判断，是否开启自动配置。
		// 默认情况下（未配置），开启自动配置。
		if (getClass() == AutoConfigurationImportSelector.class) {
			return getEnvironment().getProperty(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true);
		}
		return true;
	}

	/**
	 * Return the appropriate {@link AnnotationAttributes} from the
	 * {@link AnnotationMetadata}. By default this method will return attributes for
	 * {@link #getAnnotationClass()}.
	 * @param metadata the annotation metadata
	 * @return annotation attributes
	 */
	protected AnnotationAttributes getAttributes(AnnotationMetadata metadata) {
		//此处 getAnnotationClass().getName() 返回的是 @EnableAutoConfiguration 注解，所以这里返回的注解属性，只能是 exclude 和 excludeName 这两个。
		String name = getAnnotationClass().getName();
		// 获得注解的属性
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(metadata.getAnnotationAttributes(name, true));
		Assert.notNull(attributes, () -> "No auto-configuration attributes found. Is " + metadata.getClassName()
				+ " annotated with " + ClassUtils.getShortName(name) + "?");
		return attributes;
	}

	/**
	 * Return the source annotation class used by the selector.
	 * @return the annotation class
	 */
	protected Class<?> getAnnotationClass() {
		return EnableAutoConfiguration.class;
	}

	/**
	 * Return the auto-configuration class names that should be considered. By default
	 * this method will load candidates using {@link SpringFactoriesLoader} with
	 * {@link #getSpringFactoriesLoaderFactoryClass()}.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return a list of candidate configurations
	 *
	 * @tips 获得符合代码条件的配置类的目录。
	 */
	protected List<String> getCandidateConfigurations(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		// <1> 加载指定类型 EnableAutoConfiguration 的，在 `META-INF/ spring.factories`里的类名的目录中)
		List<String> configurations = SpringFactoriesLoader.loadFactoryNames(getSpringFactoriesLoaderFactoryClass(),
				getBeanClassLoader());
		// 断言，非空 "在 META-INF/spring.factories 中没有找到自动配置类。如果你 " + " 使用自定义包装，请确保该文件是正确的。“
		Assert.notEmpty(configurations, "No auto configuration classes found in META-INF/spring.factories. If you "
				+ "are using a custom packaging, make sure that file is correct.");
		return configurations;
	}

	/**
	 * Return the class used by {@link SpringFactoriesLoader} to load configuration
	 * candidates.
	 * @return the factory class
	 */
	protected Class<?> getSpringFactoriesLoaderFactoryClass() {
		return EnableAutoConfiguration.class;
	}

	private void checkExcludedClasses(List<String> configurations, Set<String> exclusions) {
		// 获得 exclusions 不在 invalidExcludes 的集合，添加到 invalidExcludes 中
		List<String> invalidExcludes = new ArrayList<>(exclusions.size());
		for (String exclusion : exclusions) {
			// // classpath 存在该类												// configurations 不存在该类
			// 不合法的定义，exclusions 存在于 classpath 中，但是不存在 configurations 。这样做的目的是，如果不存在的，就不要去排除啦！
			if (ClassUtils.isPresent(exclusion, getClass().getClassLoader()) && !configurations.contains(exclusion)) {
				invalidExcludes.add(exclusion);
			}
		}
		// 如果 invalidExcludes 非空，抛出 IllegalStateException 异常
		if (!invalidExcludes.isEmpty()) {
			handleInvalidExcludes(invalidExcludes);
		}
	}

	/**
	 * Handle any invalid excludes that have been specified.
	 * @param invalidExcludes the list of invalid excludes (will always have at least one
	 * element)
	 */
	protected void handleInvalidExcludes(List<String> invalidExcludes) {
		StringBuilder message = new StringBuilder();
		for (String exclude : invalidExcludes) {
			message.append("\t- ").append(exclude).append(String.format("%n"));
		}
		throw new IllegalStateException(String.format(
				"The following classes could not be excluded because they are" + " not auto-configuration classes:%n%s",
				message));
	}

	/**
	 * Return any exclusions that limit the candidate configurations.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return exclusions or an empty set
	 */
	protected Set<String> getExclusions(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		Set<String> excluded = new LinkedHashSet<>();
		// 注解上的 exclude 属性
		excluded.addAll(asList(attributes, "exclude"));
		// 注解上的 excludeName 属性
		excluded.addAll(Arrays.asList(attributes.getStringArray("excludeName")));
		// 配置文件的 spring.autoconfigure.exclude 属性
		excluded.addAll(getExcludeAutoConfigurationsProperty());
		return excluded;
	}

	/**
	 * 一共有三种方式，配置排除属性。
	 * @return
	 */
	private List<String> getExcludeAutoConfigurationsProperty() {
		// 一般来说，会走这块的逻辑
		if (getEnvironment() instanceof ConfigurableEnvironment) {
			Binder binder = Binder.get(getEnvironment());
			return binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class).map(Arrays::asList)
					.orElse(Collections.emptyList());
		}
		String[] excludes = getEnvironment().getProperty(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class);
		return (excludes != null) ? Arrays.asList(excludes) : Collections.emptyList();
	}

	private List<String> filter(List<String> configurations, AutoConfigurationMetadata autoConfigurationMetadata) {
		long startTime = System.nanoTime();
		String[] candidates = StringUtils.toStringArray(configurations);
		boolean[] skip = new boolean[candidates.length];
		boolean skipped = false;
		for (AutoConfigurationImportFilter filter : getAutoConfigurationImportFilters()) {
			invokeAwareMethods(filter);
			boolean[] match = filter.match(candidates, autoConfigurationMetadata);
			for (int i = 0; i < match.length; i++) {
				if (!match[i]) {
					skip[i] = true;
					candidates[i] = null;
					skipped = true;
				}
			}
		}
		if (!skipped) {
			return configurations;
		}
		List<String> result = new ArrayList<>(candidates.length);
		for (int i = 0; i < candidates.length; i++) {
			if (!skip[i]) {
				result.add(candidates[i]);
			}
		}
		if (logger.isTraceEnabled()) {
			int numberFiltered = configurations.size() - result.size();
			logger.trace("Filtered " + numberFiltered + " auto configuration class in "
					+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) + " ms");
		}
		return new ArrayList<>(result);
	}

	protected List<AutoConfigurationImportFilter> getAutoConfigurationImportFilters() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, this.beanClassLoader);
	}

	protected final <T> List<T> removeDuplicates(List<T> list) {
		return new ArrayList<>(new LinkedHashSet<>(list));
	}

	protected final List<String> asList(AnnotationAttributes attributes, String name) {
		String[] value = attributes.getStringArray(name);
		return Arrays.asList((value != null) ? value : new String[0]);
	}

	private void fireAutoConfigurationImportEvents(List<String> configurations, Set<String> exclusions) {
		// <1> 加载指定类型 AutoConfigurationImportListener 对应的，在 `META-INF/spring.factories` 里的类名的数组。
		List<AutoConfigurationImportListener> listeners = getAutoConfigurationImportListeners();
		if (!listeners.isEmpty()) {
			// <2> 创建 AutoConfigurationImportEvent 事件
			AutoConfigurationImportEvent event = new AutoConfigurationImportEvent(this, configurations, exclusions);
			// <3> 遍历 AutoConfigurationImportListener 监听器们，逐个通知
			for (AutoConfigurationImportListener listener : listeners) {
				// <3.1> 设置 AutoConfigurationImportListener 的属性
				invokeAwareMethods(listener);
				// <3.2> 通知 目前只有一个 ConditionEvaluationReportAutoConfigurationImportListener 监听器，没啥逻辑，
				listener.onAutoConfigurationImportEvent(event);
			}
		}
	}

	protected List<AutoConfigurationImportListener> getAutoConfigurationImportListeners() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportListener.class, this.beanClassLoader);
	}

	private void invokeAwareMethods(Object instance) {
		// 各种 Aware 属性的注入
		if (instance instanceof Aware) {
			if (instance instanceof BeanClassLoaderAware) {
				((BeanClassLoaderAware) instance).setBeanClassLoader(this.beanClassLoader);
			}
			if (instance instanceof BeanFactoryAware) {
				((BeanFactoryAware) instance).setBeanFactory(this.beanFactory);
			}
			if (instance instanceof EnvironmentAware) {
				((EnvironmentAware) instance).setEnvironment(this.environment);
			}
			if (instance instanceof ResourceLoaderAware) {
				((ResourceLoaderAware) instance).setResourceLoader(this.resourceLoader);
			}
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	protected final ConfigurableListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	protected final Environment getEnvironment() {
		return this.environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	protected final ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	/**
	 * AutoConfigurationGroup ，是 AutoConfigurationImportSelector 的内部实现类，
	 * 实现 DeferredImportSelector.Group、BeanClassLoaderAware、BeanFactoryAware、ResourceLoaderAware 接口，自动配置的 Group 实现类。
	 */
	private static class AutoConfigurationGroup
			implements DeferredImportSelector.Group, BeanClassLoaderAware, BeanFactoryAware, ResourceLoaderAware {

		/**
		 * AnnotationMetadata 的映射
		 *
		 * KEY：配置类的全类名
		 */
		private final Map<String, AnnotationMetadata> entries = new LinkedHashMap<>();

		/**
		 * AutoConfigurationEntry 的目录
		 * autoConfigurationEntries属性，AutoConfigurationEntry 的目录。
		 *
		 * 其中，AutoConfigurationEntry 是 AutoConfigurationImportSelector 的内部类，自动配置的条目
		 */
		private final List<AutoConfigurationEntry> autoConfigurationEntries = new ArrayList<>();

		//私有类加载器
		private ClassLoader beanClassLoader;

		//私有BeanFactory
		private BeanFactory beanFactory;

		//私有资源加载器资源加载器；
		private ResourceLoader resourceLoader;

		//自动配置的元数据
		private AutoConfigurationMetadata autoConfigurationMetadata;

		@Override
		public void setBeanClassLoader(ClassLoader classLoader) {
			this.beanClassLoader = classLoader;
		}

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		/**
		 * annotationMetadata参数，一般来说是被@SpringBootApplication注解的元数据。因为，@SpringBootApplication组合了@EnableAutoConfiguration注解。
		 * deferredImportSelector参数，@EnableAutoConfiguration注解定义的@Import类，即 AutoConfigurationImportSelector 对象。
		 * @param annotationMetadata
		 * @param deferredImportSelector
		 */
		@Override
		public void process(AnnotationMetadata annotationMetadata, DeferredImportSelector deferredImportSelector) {
			Assert.state(deferredImportSelector instanceof AutoConfigurationImportSelector,
					() -> String.format("Only %s implementations are supported, got %s",
							AutoConfigurationImportSelector.class.getSimpleName(),
							deferredImportSelector.getClass().getName()));
			// <1> 获得 AutoConfigurationEntry 对象
			AutoConfigurationEntry autoConfigurationEntry = ((AutoConfigurationImportSelector) deferredImportSelector)
					.getAutoConfigurationEntry(getAutoConfigurationMetadata(), annotationMetadata);
			// <2> 添加到 autoConfigurationEntries 中
			this.autoConfigurationEntries.add(autoConfigurationEntry);
			// <3> 添加到条目中
			for (String importClassName : autoConfigurationEntry.getConfigurations()) {
				this.entries.putIfAbsent(importClassName, annotationMetadata);
			}
		}

		/**
		 * 获得要引入的配置类。
		 * @return
		 */
		@Override
		public Iterable<Entry> selectImports() {
			// <1>如果为空，则返回空目录
			if (this.autoConfigurationEntries.isEmpty()) {
				return Collections.emptyList();
			}
			// <2.1> 获得 allExclusions
			Set<String> allExclusions = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getExclusions).flatMap(Collection::stream).collect(Collectors.toSet());
			// <2.2> 获取已处理配置
			Set<String> processedConfigurations = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getConfigurations).flatMap(Collection::stream)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			// <2.3> 从handledConfigurations中，移除排除的
			processedConfigurations.removeAll(allExclusions);

			// <3> 处理，返回结果
			return sortAutoConfigurations(processedConfigurations, getAutoConfigurationMetadata()).stream()
					.map((importClassName) -> new Entry(this.entries.get(importClassName), importClassName))
					.collect(Collectors.toList());
		}

		/**
		 * 通过#getAutoConfigurationMetadata()，方法会初始化该属性。
		 * @return
		 */
		private AutoConfigurationMetadata getAutoConfigurationMetadata() {
			if (this.autoConfigurationMetadata == null) {
				this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
			}
			return this.autoConfigurationMetadata;
		}

		private List<String> sortAutoConfigurations(Set<String> configurations,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			return new AutoConfigurationSorter(getMetadataReaderFactory(), autoConfigurationMetadata)
					.getInPriorityOrder(configurations);
		}

		private MetadataReaderFactory getMetadataReaderFactory() {
			try {
				return this.beanFactory.getBean(SharedMetadataReaderFactoryContextInitializer.BEAN_NAME,
						MetadataReaderFactory.class);
			}
			catch (NoSuchBeanDefinitionException ex) {
				return new CachingMetadataReaderFactory(this.resourceLoader);
			}
		}

	}


	protected static class AutoConfigurationEntry {

		//配置类的全类名的目录
		private final List<String> configurations;

		//排除的类的全类配置
		private final Set<String> exclusions;

		private AutoConfigurationEntry() {
			this.configurations = Collections.emptyList();
			this.exclusions = Collections.emptySet();
		}

		/**
		 * Create an entry with the configurations that were contributed and their
		 * exclusions.
		 * @param configurations the configurations that should be imported
		 * @param exclusions the exclusions that were applied to the original list
		 */
		AutoConfigurationEntry(Collection<String> configurations, Collection<String> exclusions) {
			this.configurations = new ArrayList<>(configurations);
			this.exclusions = new HashSet<>(exclusions);
		}

		public List<String> getConfigurations() {
			return this.configurations;
		}

		public Set<String> getExclusions() {
			return this.exclusions;
		}

	}

}
