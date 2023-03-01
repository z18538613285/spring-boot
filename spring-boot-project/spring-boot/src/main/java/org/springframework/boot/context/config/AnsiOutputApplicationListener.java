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

package org.springframework.boot.context.config;

import org.springframework.boot.ansi.AnsiOutput;
import org.springframework.boot.ansi.AnsiOutput.Enabled;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * An {@link ApplicationListener} that configures {@link AnsiOutput} depending on the
 * value of the property {@code spring.output.ansi.enabled}. See {@link Enabled} for valid
 * values.
 *
 * @author Raphael von der Grün
 * @author Madhura Bhave
 * @since 1.2.0
 *
 * @tips 在 Spring Boot 环境变量(environment)准备完成以后运行，
 * 如果你的终端支持 ANSI ，设置彩色输出会让日志更具可读性。
 */
public class AnsiOutputApplicationListener
		implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		// <2> 根据环境变量 spring.output.ansi.enabled 的值，设置 AnsiOutput.enabled 属性
		ConfigurableEnvironment environment = event.getEnvironment();
		Binder.get(environment).bind("spring.output.ansi.enabled", AnsiOutput.Enabled.class)
				.ifBound(AnsiOutput::setEnabled);
		// <3> 根据环境变量 "spring.output.ansi.console-available 的值，设置 AnsiOutput.consoleAvailable 属性
		AnsiOutput.setConsoleAvailable(environment.getProperty("spring.output.ansi.console-available", Boolean.class));
		/**
		 * 为什么结果会是 AnsiOutput.Enabled.ALWAYS ，在 IDEA 环境中。后来，在 environment 中，一个名字是 "systemProperties" 的 MapPropertySource 属性源，
		 * 里面提供了 "spring.output.ansi.enabled=always" 的配置。
		 *
		 * 后来发现，"systemProperties" 这个 MapPropertySource 属性源，读取的是 System#getProperties() 方法，
		 * 但是为啥里面会有 "spring.output.ansi.enabled=always" 呢？
		 * 目前的猜测是，IDEA 判断在 Spring Boot 环境下，自动添加进去的！
		 */
	}

	@Override
	public int getOrder() {
		// Apply after ConfigFileApplicationListener has called EnvironmentPostProcessors
		return ConfigFileApplicationListener.DEFAULT_ORDER + 1;
	}

}
