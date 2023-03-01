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

import org.springframework.boot.loader.archive.Archive;

/**
 * {@link Launcher} for JAR based archives. This launcher assumes that dependency jars are
 * included inside a {@code /BOOT-INF/lib} directory and that application classes are
 * included inside a {@code /BOOT-INF/classes} directory.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @since 1.0.0
 */
public class JarLauncher extends ExecutableArchiveLauncher {

	static final String BOOT_INF_CLASSES = "BOOT-INF/classes/";

	static final String BOOT_INF_LIB = "BOOT-INF/lib/";

	public JarLauncher() {
	}

	protected JarLauncher(Archive archive) {
		super(archive);
	}

	/**
	 * 创建了 EntryFilter 匿名实现类，用于过滤 jar 包不需要的目录。
	 * @param entry the jar entry
	 * @return
	 *
	 * @tips 目的就是过滤获得，BOOT-INF/classes/ 目录下的类，以及 BOOT-INF/lib/ 的内嵌 jar 包。
	 */
	@Override
	protected boolean isNestedArchive(Archive.Entry entry) {
		// 如果是目录的情况，只要 BOOT-INF/classes/ 目录
		if (entry.isDirectory()) {
			return entry.getName().equals(BOOT_INF_CLASSES);
		}
		// 如果是文件的情况，只要 BOOT-INF/lib/ 目录下的 `jar` 包
		return entry.getName().startsWith(BOOT_INF_LIB);
	}

	public static void main(String[] args) throws Exception {
		// 整体的启动逻辑，由父类 Launcher 提供
		new JarLauncher().launch(args);
	}

}
