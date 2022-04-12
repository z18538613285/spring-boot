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

package org.springframework.boot.web.reactive.context;

import java.util.function.Supplier;

import reactor.core.publisher.Mono;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContextException;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;

/**
 * A {@link GenericReactiveWebApplicationContext} that can be used to bootstrap itself
 * from a contained {@link ReactiveWebServerFactory} bean.
 *
 * @author Brian Clozel
 * @since 2.0.0
 * @tips 实现 ConfigurableWebServerApplicationContext 接口，继承 GenericReactiveWebApplicationContext 类，
 * Spring Boot 使用 Reactive Web 服务器的 ApplicationContext 实现类。
 */
public class ReactiveWebServerApplicationContext extends GenericReactiveWebApplicationContext
		implements ConfigurableWebServerApplicationContext {

	private volatile ServerManager serverManager;

	private String serverNamespace;

	/**
	 * Create a new {@link ReactiveWebServerApplicationContext}.
	 */
	public ReactiveWebServerApplicationContext() {
	}

	/**
	 * Create a new {@link ReactiveWebServerApplicationContext} with the given
	 * {@code DefaultListableBeanFactory}.
	 * @param beanFactory the DefaultListableBeanFactory instance to use for this context
	 */
	public ReactiveWebServerApplicationContext(DefaultListableBeanFactory beanFactory) {
		super(beanFactory);
	}

	/**
	 * 覆写 #refresh() 方法，初始化 Spring 容器。
	 * @throws BeansException
	 * @throws IllegalStateException
	 */
	@Override
	public final void refresh() throws BeansException, IllegalStateException {
		try {
			// 调用父方法
			super.refresh();
		}
		catch (RuntimeException ex) {
			// <X> 停止 Reactive WebServer
			stopAndReleaseReactiveWebServer();
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
			// <2> 创建 WebServer
			createWebServer();
		}
		catch (Throwable ex) {
			throw new ApplicationContextException("Unable to start reactive web server", ex);
		}
	}

	private void createWebServer() {
		// 获得 ServerManager 对象。
		ServerManager serverManager = this.serverManager;
		// 如果不存在，则进行初始化
		if (serverManager == null) {
			// 获得 ReactiveWebServerFactory 对象。
			this.serverManager = ServerManager.get(getWebServerFactory());
		}
		// <2> 初始化 PropertySource
		initPropertySources();
	}

	/**
	 * Return the {@link ReactiveWebServerFactory} that should be used to create the
	 * reactive web server. By default this method searches for a suitable bean in the
	 * context itself.
	 * @return a {@link ReactiveWebServerFactory} (never {@code null})
	 *
	 * @tips 默认情况下，此处返回的会是 org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory 对象。
	 * 在我们引入 spring-boot-starter-webflux 依赖时，org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryConfiguration 在自动配置时，
	 * 会配置出 NettyReactiveWebServerFactory Bean 对象。因此，此时会获得 NettyReactiveWebServerFactory 对象。
	 */
	protected ReactiveWebServerFactory getWebServerFactory() {
		// Use bean names so that we don't consider the hierarchy
		// 获得 ServletWebServerFactory 类型对应的 Bean 的名字们
		String[] beanNames = getBeanFactory().getBeanNamesForType(ReactiveWebServerFactory.class);
		// 如果是 0 个，抛出 ApplicationContextException 异常，因为至少要一个
		if (beanNames.length == 0) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to missing " + "ReactiveWebServerFactory bean.");
		}
		// 如果是 > 1 个，抛出 ApplicationContextException 异常，因为不知道初始化哪个
		if (beanNames.length > 1) {
			throw new ApplicationContextException("Unable to start ReactiveWebApplicationContext due to multiple "
					+ "ReactiveWebServerFactory beans : " + StringUtils.arrayToCommaDelimitedString(beanNames));
		}
		// 获得 ReactiveWebServerFactory 类型对应的 Bean 对象
		return getBeanFactory().getBean(beanNames[0], ReactiveWebServerFactory.class);
	}

	/**
	 * 覆写 #finishRefresh() 方法，在容器初始化完成时，启动 WebServer 。
	 */
	@Override
	protected void finishRefresh() {
		// <1> 调用父方法
		super.finishRefresh();
		// <2> 启动 WebServer
		WebServer webServer = startReactiveWebServer();
		// <3> 如果创建 WebServer 成功，发布 ReactiveWebServerInitializedEvent 事件
		if (webServer != null) {
			publishEvent(new ReactiveWebServerInitializedEvent(webServer, this));
		}
	}

	private WebServer startReactiveWebServer() {
		ServerManager serverManager = this.serverManager;
		// <1> 获得 HttpHandler
		// <2> 启动 WebServer			调用 #getHttpHandler() 方法，获得 HttpHandler 对象。
		ServerManager.start(serverManager, this::getHttpHandler);
		// <3> 获得 WebServer
		return ServerManager.getWebServer(serverManager);
	}

	/**
	 * Return the {@link HttpHandler} that should be used to process the reactive web
	 * server. By default this method searches for a suitable bean in the context itself.
	 * @return a {@link HttpHandler} (never {@code null}
	 *
	 * @tips 该 HttpHandler Bean 对象，是在 org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration 配置类上，被初始化出来。
	 */
	protected HttpHandler getHttpHandler() {
		// Use bean names so that we don't consider the hierarchy
		// 获得 HttpHandler 类型对应的 Bean 的名字们
		String[] beanNames = getBeanFactory().getBeanNamesForType(HttpHandler.class);
		// 如果是 0 个，抛出 ApplicationContextException 异常，因为至少要一个
		if (beanNames.length == 0) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to missing HttpHandler bean.");
		}
		// 如果是 > 1 个，抛出 ApplicationContextException 异常，因为不知道初始化哪个
		if (beanNames.length > 1) {
			throw new ApplicationContextException(
					"Unable to start ReactiveWebApplicationContext due to multiple HttpHandler beans : "
							+ StringUtils.arrayToCommaDelimitedString(beanNames));
		}
		// 获得 HttpHandler 类型对应的 Bean 对象
		return getBeanFactory().getBean(beanNames[0], HttpHandler.class);
	}

	/**
	 * 覆写 #onClose() 方法，在 Spring 容器被关闭时，关闭 WebServer 。
	 */
	@Override
	protected void onClose() {
		// 调用父类方法
		super.onClose();
		// 关闭 WebServer
		stopAndReleaseReactiveWebServer();
	}

	private void stopAndReleaseReactiveWebServer() {
		ServerManager serverManager = this.serverManager;
		try {
			// 调用 ServerManager#stop(serverManager) 方法，停止 WebServer 。
			ServerManager.stop(serverManager);
		}
		finally {
			this.serverManager = null;
		}
	}

	/**
	 * Returns the {@link WebServer} that was created by the context or {@code null} if
	 * the server has not yet been created.
	 * @return the web server
	 */
	@Override
	public WebServer getWebServer() {
		return ServerManager.getWebServer(this.serverManager);
	}

	@Override
	public String getServerNamespace() {
		return this.serverNamespace;
	}

	@Override
	public void setServerNamespace(String serverNamespace) {
		this.serverNamespace = serverNamespace;
	}

	/**
	 * Internal class used to manage the server and the {@link HttpHandler}, taking care
	 * not to initialize the handler too early.
	 *
	 * @tips ServerManager 是 ReactiveWebServerApplicationContext 的内部静态类，
	 * 实现 org.springframework.http.server.reactive.HttpHandler 接口，内含 Server 的管理器。
	 */
	static final class ServerManager implements HttpHandler {

		private final WebServer server;
		/**
		 * HttpHandler 对象，具体在 {@link #handle(ServerHttpRequest, ServerHttpResponse)} 方法中使用。
		 */
		private volatile HttpHandler handler;

		private ServerManager(ReactiveWebServerFactory factory) {
			this.handler = this::handleUninitialized;
			/**
			 * 创建 WebServer 对象。其中，方法参数 handler 传递是 this 自己，
			 * 因为后面的请求处理的 #handle(ServerHttpRequest request, ServerHttpResponse) 方法，需要调用自己哟。
			 */
			this.server = factory.getWebServer(this);
		}

		// 会抛出 IllegalStateException 异常。表示，此时该 server 还不可用。因为，server 都还没启动。
		private Mono<Void> handleUninitialized(ServerHttpRequest request, ServerHttpResponse response) {
			throw new IllegalStateException("The HttpHandler has not yet been initialized");
		}

		@Override
		public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
			// 委托给 handler 属性，对应的方法，处理请求。
			return this.handler.handle(request, response);
		}

		public HttpHandler getHandler() {
			return this.handler;
		}

		public static ServerManager get(ReactiveWebServerFactory factory) {
			// 创建一个 ServerManager 对象。
			return new ServerManager(factory);
		}

		/**
		 * 获得 manager 的 WebServer 对象。
		 * @param manager
		 * @return
		 */
		public static WebServer getWebServer(ServerManager manager) {
			return (manager != null) ? manager.server : null;
		}

		/**
		 * 静态方法，启动。
		 * @param manager
		 * @param handlerSupplier
		 */
		public static void start(ServerManager manager, Supplier<HttpHandler> handlerSupplier) {
			if (manager != null && manager.server != null) {
				// <1> 赋值 handler
				manager.handler = handlerSupplier.get();
				// <2> 启动 server
				manager.server.start();
			}
		}

		//静态方法，停止。
		public static void stop(ServerManager manager) {
			if (manager != null && manager.server != null) {
				try {
					manager.server.stop();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
		}

	}

}
