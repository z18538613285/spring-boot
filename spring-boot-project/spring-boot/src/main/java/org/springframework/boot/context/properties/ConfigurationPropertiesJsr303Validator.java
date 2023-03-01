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

package org.springframework.boot.context.properties;

import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Validator that supports configuration classes annotated with
 * {@link Validated @Validated}.
 *
 * @author Phillip Webb
 *
 * @tips @ConfigurationProperties + @Validated 注解的 Bean 的 JSR303 的 Validator 实现类。
 *
 */
final class ConfigurationPropertiesJsr303Validator implements Validator {

	private static final String[] VALIDATOR_CLASSES = { "javax.validation.Validator",
			"javax.validation.ValidatorFactory", "javax.validation.bootstrap.GenericBootstrap" };

	private final Delegate delegate;

	ConfigurationPropertiesJsr303Validator(ApplicationContext applicationContext) {
		this.delegate = new Delegate(applicationContext);
	}

	// 判断是否支持指定类的校验。
	@Override
	public boolean supports(Class<?> type) {
		return this.delegate.supports(type);
	}

	@Override
	public void validate(Object target, Errors errors) {
		this.delegate.validate(target, errors);
	}

	// 校验是否支持 JSR303 。
	public static boolean isJsr303Present(ApplicationContext applicationContext) {
		ClassLoader classLoader = applicationContext.getClassLoader();
		for (String validatorClass : VALIDATOR_CLASSES) {
			if (!ClassUtils.isPresent(validatorClass, classLoader)) {
				return false;
			}
		}
		return true;
	}

	private static class Delegate extends LocalValidatorFactoryBean {

		Delegate(ApplicationContext applicationContext) {
			// 设置 applicationContext 属性
			setApplicationContext(applicationContext);
			// 设置 messageInterpolator 属性
			setMessageInterpolator(new MessageInterpolatorFactory().getObject());
			// 回调 afterPropertiesSet 方法
			afterPropertiesSet();
		}

	}

}
