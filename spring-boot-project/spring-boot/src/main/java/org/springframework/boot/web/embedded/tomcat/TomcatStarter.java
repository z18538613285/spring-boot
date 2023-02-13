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

package org.springframework.boot.web.embedded.tomcat;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.web.servlet.ServletContextInitializer;

/**
 * {@link ServletContainerInitializer} used to trigger {@link ServletContextInitializer
 * ServletContextInitializers} and track startup errors.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 *
 * @tips 当使用内嵌的 Tomcat 时，你会发现 Spring Boot 完全走了另一套初始化流程，
 * 完全没有使用前面提到的 SpringServletContainerInitializer ，而是进入了该类
 *
 * Spring Boot 考虑到了如下的问题，我们在使用 Spring Boot 时，开发阶段一般都是使用内嵌 Tomcat 容器，
 * 但部署时却存在两种选择：一种是打成 jar 包，使用 java -jar 的方式运行；另一种是打成 war 包，交给外置容器去运行。
 *
 * 前者就会导致容器搜索算法出现问题，因为这是 jar 包的运行策略，不会按照 Servlet 3.0 的策略去加载 ServletContainerInitializer！
 *
 * 最后作者还提供了一个替代选项：ServletContextInitializer，注意是 ServletContextInitializer ！它和 ServletContainerInitializer 长得特别像，别搞混淆了！
 *
 * 虽然 ServletContainerInitializer 不能被内嵌容器加载，
 * ServletContextInitializer 却能被 Spring Boot 的 EmbeddedWebApplicationContext 加载到，从而装配其中的 servlet 和 filter。
 */
class TomcatStarter implements ServletContainerInitializer {

	private static final Log logger = LogFactory.getLog(TomcatStarter.class);

	// 是 Spring Boot 初始化 servlet，filter，listener 的关键。
	private final ServletContextInitializer[] initializers;

	private volatile Exception startUpException;

	TomcatStarter(ServletContextInitializer[] initializers) {
		this.initializers = initializers;
	}

	@Override
	public void onStartup(Set<Class<?>> classes, ServletContext servletContext) throws ServletException {
		try {
			// 含有 EmbeddedWebApplicationContext 的内部类
			// EmbeddedWebApplicationContext 的逻辑，应该是修改到了 ServletWebServerApplicationContext 中。
			for (ServletContextInitializer initializer : this.initializers) {
				// 第一层
				initializer.onStartup(servletContext);
			}
		}
		catch (Exception ex) {
			this.startUpException = ex;
			// Prevent Tomcat from logging and re-throwing when we know we can
			// deal with it in the main thread, but log for information here.
			if (logger.isErrorEnabled()) {
				logger.error("Error starting Tomcat context. Exception: " + ex.getClass().getName() + ". Message: "
						+ ex.getMessage());
			}
		}
	}

	public Exception getStartUpException() {
		return this.startUpException;
	}

}
