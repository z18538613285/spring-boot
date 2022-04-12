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

package org.springframework.boot.web.context;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * SPI interface to be implemented by most if not all {@link WebServerApplicationContext
 * web server application contexts}. Provides facilities to configure the context, in
 * addition to the methods in the {WebServerApplicationContext} interface.
 *
 * @author Phillip Webb
 * @since 2.0.0
 *
 * @tips 实现它后，可以获得管理 WebServer 的能力。
 * org.springframework.context.ConfigurableApplicationContext ，是 Spring Framework 提供的类，就不细看了。
 * org.springframework.boot.web.context.WebServerApplicationContext ，继承 ApplicationContext 接口，WebServer ApplicationContext 接口。
 *
 */
public interface ConfigurableWebServerApplicationContext
		extends ConfigurableApplicationContext, WebServerApplicationContext {

	/**
	 * Set the server namespace of the context.
	 * @param serverNamespace the server namespace
	 * @see #getServerNamespace()
	 */
	void setServerNamespace(String serverNamespace);

}
