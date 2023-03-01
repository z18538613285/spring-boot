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

package org.springframework.boot.context.properties.bind;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;
import org.springframework.util.Assert;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.SystemPropertyUtils;

/**
 * {@link PlaceholdersResolver} to resolve placeholders from {@link PropertySources}.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 * @since 2.0.0
 *
 * @tips 实现 PropertySource 对应的值是占位符的解析器 。
 */
public class PropertySourcesPlaceholdersResolver implements PlaceholdersResolver {

	private final Iterable<PropertySource<?>> sources;

	/**
	 * 其中，创建的 helper 属性，为 PropertyPlaceholderHelper 对象。
	 * 其中 SystemPropertyUtils.PLACEHOLDER_PREFIX 为 ${ ， SystemPropertyUtils.PLACEHOLDER_SUFFIX 为 } 。
	 * 这样，例如说 RandomValuePropertySource 的 ${random.int} 等等，就可以被 PropertySourcesPlaceholdersResolver 所处理。
	 */
	private final PropertyPlaceholderHelper helper;

	public PropertySourcesPlaceholdersResolver(Environment environment) {
		this(getSources(environment), null);
	}

	public PropertySourcesPlaceholdersResolver(Iterable<PropertySource<?>> sources) {
		this(sources, null);
	}

	public PropertySourcesPlaceholdersResolver(Iterable<PropertySource<?>> sources, PropertyPlaceholderHelper helper) {
		this.sources = sources;
		this.helper = (helper != null) ? helper : new PropertyPlaceholderHelper(SystemPropertyUtils.PLACEHOLDER_PREFIX,
				SystemPropertyUtils.PLACEHOLDER_SUFFIX, SystemPropertyUtils.VALUE_SEPARATOR, true);
	}

	/**
	 * 如果 value 是 String 类型，才可能是占位符。满足时，调用 PropertyPlaceholderHelper#replacePlaceholders(String value, PlaceholderResolver placeholderResolver) 方法，
	 * 解析出占位符里面的内容。
	 *
	 * 例如说：PropertySourcesPlaceholdersResolver 中，占位符是 ${} ，那么 ${random.int} 被解析后的内容是 random.int 。
	 * 解析到占位符后，则回调 #resolvePlaceholder(String placeholder) 方法，获得占位符对应的值。代码如下：
	 * @param value the source value
	 * @return
	 */
	@Override
	public Object resolvePlaceholders(Object value) {
		// 如果 value 是 String 类型，才是占位符
		if (value instanceof String) {
			return this.helper.replacePlaceholders((String) value, this::resolvePlaceholder);
		}
		return value;
	}

	protected String resolvePlaceholder(String placeholder) {
		if (this.sources != null) {
			// 遍历 sources 数组，逐个获得属性值。若获取到，则进行返回
			for (PropertySource<?> source : this.sources) {
				Object value = source.getProperty(placeholder);
				if (value != null) {
					return String.valueOf(value);
				}
			}
		}
		return null;
	}

	// // 获得 PropertySources 们
	private static PropertySources getSources(Environment environment) {
		Assert.notNull(environment, "Environment must not be null");
		Assert.isInstanceOf(ConfigurableEnvironment.class, environment,
				"Environment must be a ConfigurableEnvironment");
		return ((ConfigurableEnvironment) environment).getPropertySources();
	}

}
