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

package org.springframework.boot.loader;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.loader.archive.Archive;
import org.springframework.boot.loader.archive.ExplodedArchive;
import org.springframework.boot.loader.archive.JarFileArchive;
import org.springframework.boot.loader.jar.JarFile;

/**
 * Base class for launchers that can start an application with a fully configured
 * classpath backed by one or more {@link Archive}s.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @since 1.0.0
 */
public abstract class Launcher {

	/**
	 * Launch the application. This method is the initial entry point that should be
	 * called by a subclass {@code public static void main(String[] args)} method.
	 * @param args the incoming arguments
	 * @throws Exception if the application fails to launch
	 *
	 * @tips 就是整一个可以读取 jar 包中类的加载器，保证 BOOT-INF/lib 目录下的类和 BOOT-classes 内嵌的 jar 中的类能够被正常加载到，
	 * 之后执行 Spring Boot 应用的启动。
	 *
	 * @tips 通过 Archive，实现 jar 包的遍历，将 META-INF/classes 目录和 META-INF/lib 的每一个内嵌的 jar 解析成一个 Archive 对象。
	 * 		 通过 Handler，处理 jar: 协议的 URL 的资源读取，也就是读取了每个 Archive 里的内容。
	 * 		 通过 LaunchedURLClassLoader，实现 META-INF/classes 目录下的类和 META-INF/classes 目录下内嵌的 jar 包中的类的加载。
	 * 		 		具体的 URL 来源，是通过 Archive 提供；具体 URL 的读取，是通过 Handler 提供。
	 *
	 * 		 通过 MainMethodRunner ，实现 Spring Boot 应用的启动类的执行。
	 * 		 当然，上述的一切都是通过 Launcher 来完成引导和启动，通过 MANIFEST.MF 进行具体配置。
	 */
	protected void launch(String[] args) throws Exception {
		// 调用 JarFile 的 #registerUrlProtocolHandler() 方法，注册 Spring Boot 自定义的 URLStreamHandler 实现类，用于 jar 包的加载读取。
		// <1> 注册 URL 协议的处理器
		JarFile.registerUrlProtocolHandler();
		// 调用自身的 #createClassLoader(List<Archive> archives) 方法，创建自定义的 ClassLoader 实现类，用于从 jar 包中加载类。
		// <2> 创建类加载器
		ClassLoader classLoader = createClassLoader(getClassPathArchives());
		// 执行我们声明的 Spring Boot 启动类，进行 Spring Boot 应用的启动。
		// <3> 执行启动类的 main 方法
		launch(args, getMainClass(), classLoader);
	}

	/**
	 * Create a classloader for the specified archives.
	 * @param archives the archives
	 * @return the classloader
	 * @throws Exception if the classloader cannot be created
	 */
	protected ClassLoader createClassLoader(List<Archive> archives) throws Exception {
		List<URL> urls = new ArrayList<>(archives.size());
		// 获得所有 Archive 的 URL 地址
		for (Archive archive : archives) {
			urls.add(archive.getUrl());
		}
		// 创建加载这些 URL 的 ClassLoader
		return createClassLoader(urls.toArray(new URL[0]));
	}

	/**
	 * Create a classloader for the specified URLs.
	 * @param urls the URLs
	 * @return the classloader
	 * @throws Exception if the classloader cannot be created
	 *
	 * @tips 基于获得的 Archive 数组，创建自定义 ClassLoader 实现类 LaunchedURLClassLoader，
	 * 通过它来加载 BOOT-INF/classes 目录下的类，以及 BOOT-INF/lib 目录下的 jar 包中的类。
	 */
	protected ClassLoader createClassLoader(URL[] urls) throws Exception {
		return new LaunchedURLClassLoader(urls, getClass().getClassLoader());
	}

	/**
	 * Launch the application given the archive file and a fully configured classloader.
	 * @param args the incoming arguments
	 * @param mainClass the main class to run
	 * @param classLoader the classloader
	 * @throws Exception if the launch fails
	 */
	protected void launch(String[] args, String mainClass, ClassLoader classLoader) throws Exception {
		//// <1> 设置 LaunchedURLClassLoader 作为类加载器，从而保证能够从 jar 加载到相应的类。
		Thread.currentThread().setContextClassLoader(classLoader);
		// <2> 创建 MainMethodRunner 对象，并执行 run 方法，启动 Spring Boot 应用
		createMainMethodRunner(mainClass, args, classLoader).run();
	}

	/**
	 * Create the {@code MainMethodRunner} used to launch the application.
	 * @param mainClass the main class
	 * @param args the incoming arguments
	 * @param classLoader the classloader
	 * @return the main method runner
	 */
	protected MainMethodRunner createMainMethodRunner(String mainClass, String[] args, ClassLoader classLoader) {
		return new MainMethodRunner(mainClass, args);
	}

	/**
	 * Returns the main class that should be launched.
	 * @return the name of the main class
	 * @throws Exception if the main class cannot be obtained
	 */
	protected abstract String getMainClass() throws Exception;

	/**
	 * Returns the archives that will be used to construct the class path.
	 * @return the class path archives
	 * @throws Exception if the class path archives cannot be obtained
	 *
	 * @tips 它是由 ExecutableArchiveLauncher 所实现
	 */
	protected abstract List<Archive> getClassPathArchives() throws Exception;

	/**
	 * 根据根路径是否为目录的情况，创建 ExplodedArchive 或 JarFileArchive 对象。那么问题就来了，这里的 root 是什么呢？
	 * root 路径为 jar 包的绝对地址，也就是说创建 JarFileArchive 对象。原因是，Launcher 所在包为 org 下，它的根目录当然是 jar 包的绝对路径哈！
	 *
	 * @return
	 * @throws Exception
	 */
	protected final Archive createArchive() throws Exception {
		// 获得 jar 所在的绝对路径
		ProtectionDomain protectionDomain = getClass().getProtectionDomain();
		CodeSource codeSource = protectionDomain.getCodeSource();
		URI location = (codeSource != null) ? codeSource.getLocation().toURI() : null;
		String path = (location != null) ? location.getSchemeSpecificPart() : null;
		if (path == null) {
			throw new IllegalStateException("Unable to determine code source archive");
		}
		File root = new File(path);
		if (!root.exists()) {
			throw new IllegalStateException("Unable to determine code source archive from " + root);
		}
		// 如果是目录，则使用 ExplodedArchive 进行展开
		// 如果不是目录，则使用 JarFileArchive
		//root 路径为 jar 包的绝对地址，也就是说创建 JarFileArchive 对象。原因是，Launcher 所在包为 org 下，它的根目录当然是 jar 包的绝对路径哈！
		//BOOT-INF/classes/ 目录被归类为一个 Archive 对象，而 BOOT-INF/lib/ 目录下的每个内嵌 jar 包都对应一个 Archive 对象。
		return (root.isDirectory() ? new ExplodedArchive(root) : new JarFileArchive(root));
	}

}
