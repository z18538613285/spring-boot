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

package org.springframework.boot.autoconfigure.condition;

import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionMessage.Style;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * {@link Condition} and {@link AutoConfigurationImportFilter} that checks for the
 * presence or absence of specific classes.
 *
 * @author Phillip Webb
 * @see ConditionalOnClass
 * @see ConditionalOnMissingClass
 *
 * @tips 继承 FilteringSpringBootCondition 抽象类，给 @ConditionalOnClass、@ConditionalOnMissingClass 使用的 Condition 实现类。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
class OnClassCondition extends FilteringSpringBootCondition {

	/**
	 * // 来自 FilteringSpringBootCondition 抽象类
	 * @param autoConfigurationClasses
	 * @param autoConfigurationMetadata
	 * @return
	 */
	@Override
	protected final ConditionOutcome[] getOutcomes(String[] autoConfigurationClasses,
			AutoConfigurationMetadata autoConfigurationMetadata) {
		// Split the work and perform half in a background thread. Using a single
		// additional thread seems to offer the best performance. More threads make
		// things worse
		// <1> 在后台线程中将工作一分为二。原因是：
		// * 使用单一附加线程，似乎提供了最好的性能。
		// * 多个线程，使事情变得更糟
		/**
		 * 考虑到配置类（Configuration）配置的 @ConditionalOnClass、@ConditionalOnMissingClass 注解中的类可能比较多，
		 * 所以采用多线程提升效率。但是经过测试，分成两个线程，效率是最好的，所以这里才出现了 autoConfigurationClasses.length / 2 代码。
		 *
		 * 在 ThreadedOutcomesResolver 的实现里，会使用 Thread#join() 方法，保证新起的线程，能完成它的任务。
		 * 这也是为什么，ThreadedOutcomesResolver 后执行的原因。
		 */
		int split = autoConfigurationClasses.length / 2;
		// <2.1> 将前一半，创建一个 OutcomesResolver 对象（新线程）
		OutcomesResolver firstHalfResolver = createOutcomesResolver(autoConfigurationClasses, 0, split,
				autoConfigurationMetadata);
		// <2.2> 将后一半，创建一个 OutcomesResolver 对象
		OutcomesResolver secondHalfResolver = new StandardOutcomesResolver(autoConfigurationClasses, split,
				autoConfigurationClasses.length, autoConfigurationMetadata, getBeanClassLoader());
		// 执行解析（匹配）
		ConditionOutcome[] secondHalf = secondHalfResolver.resolveOutcomes();
		ConditionOutcome[] firstHalf = firstHalfResolver.resolveOutcomes();
		// <4> 创建 outcomes 结果数组，然后合并结果，最后返回
		ConditionOutcome[] outcomes = new ConditionOutcome[autoConfigurationClasses.length];
		System.arraycopy(firstHalf, 0, outcomes, 0, firstHalf.length);
		System.arraycopy(secondHalf, 0, outcomes, split, secondHalf.length);
		return outcomes;
	}

	private OutcomesResolver createOutcomesResolver(String[] autoConfigurationClasses, int start, int end,
			AutoConfigurationMetadata autoConfigurationMetadata) {
		OutcomesResolver outcomesResolver = new StandardOutcomesResolver(autoConfigurationClasses, start, end,
				autoConfigurationMetadata, getBeanClassLoader());
		try {
			return new ThreadedOutcomesResolver(outcomesResolver);
		}
		catch (AccessControlException ex) {
			return outcomesResolver;
		}
	}

	/**
	 * // 来自 SpringBootCondition 抽象类
	 *
	 * @param context the condition context
	 * @param metadata the annotation metadata
	 * @return
	 */
	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		// <1> 声明变量
		ClassLoader classLoader = context.getClassLoader();
		// 匹配的信息
		ConditionMessage matchMessage = ConditionMessage.empty();
		// <2> 获得 `@ConditionalOnClass` 注解的属性
		List<String> onClasses = getCandidates(metadata, ConditionalOnClass.class);
		if (onClasses != null) {
			// 执行匹配，看看是否有缺失的
			List<String> missing = filter(onClasses, ClassNameFilter.MISSING, classLoader);
			// 如果有不匹配的，返回不匹配信息
			if (!missing.isEmpty()) {
				return ConditionOutcome.noMatch(ConditionMessage.forCondition(ConditionalOnClass.class)
						.didNotFind("required class", "required classes").items(Style.QUOTE, missing));
			}
			// 如果匹配，添加到 matchMessage 中
			matchMessage = matchMessage.andCondition(ConditionalOnClass.class)
					.found("required class", "required classes")
					.items(Style.QUOTE, filter(onClasses, ClassNameFilter.PRESENT, classLoader));
		}
		// <3> 获得 `@ConditionalOnMissingClass` 注解的属性
		List<String> onMissingClasses = getCandidates(metadata, ConditionalOnMissingClass.class);
		if (onMissingClasses != null) {
			// 执行匹配，看看是有多余的
			List<String> present = filter(onMissingClasses, ClassNameFilter.PRESENT, classLoader);
			// 如果有不匹配的，返回不匹配信息
			if (!present.isEmpty()) {
				return ConditionOutcome.noMatch(ConditionMessage.forCondition(ConditionalOnMissingClass.class)
						.found("unwanted class", "unwanted classes").items(Style.QUOTE, present));
			}
			// 如果匹配，添加到 matchMessage 中
			matchMessage = matchMessage.andCondition(ConditionalOnMissingClass.class)
					.didNotFind("unwanted class", "unwanted classes")
					.items(Style.QUOTE, filter(onMissingClasses, ClassNameFilter.MISSING, classLoader));
		}
		// <4> 返回匹配的结果
		return ConditionOutcome.match(matchMessage);
	}

	private List<String> getCandidates(AnnotatedTypeMetadata metadata, Class<?> annotationType) {
		MultiValueMap<String, Object> attributes = metadata.getAllAnnotationAttributes(annotationType.getName(), true);
		if (attributes == null) {
			return null;
		}
		List<String> candidates = new ArrayList<>();
		addAll(candidates, attributes.get("value"));
		addAll(candidates, attributes.get("name"));
		return candidates;
	}

	private void addAll(List<String> list, List<Object> itemsToAdd) {
		if (itemsToAdd != null) {
			for (Object item : itemsToAdd) {
				Collections.addAll(list, (String[]) item);
			}
		}
	}

	private interface OutcomesResolver {

		ConditionOutcome[] resolveOutcomes();

	}

	private static final class ThreadedOutcomesResolver implements OutcomesResolver {

		private final Thread thread;

		private volatile ConditionOutcome[] outcomes;

		private ThreadedOutcomesResolver(OutcomesResolver outcomesResolver) {
			this.thread = new Thread(() -> this.outcomes = outcomesResolver.resolveOutcomes());
			this.thread.start();
		}

		@Override
		public ConditionOutcome[] resolveOutcomes() {
			try {
				this.thread.join();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return this.outcomes;
		}

	}

	private final class StandardOutcomesResolver implements OutcomesResolver {

		private final String[] autoConfigurationClasses;

		private final int start;

		private final int end;

		private final AutoConfigurationMetadata autoConfigurationMetadata;

		private final ClassLoader beanClassLoader;

		private StandardOutcomesResolver(String[] autoConfigurationClasses, int start, int end,
				AutoConfigurationMetadata autoConfigurationMetadata, ClassLoader beanClassLoader) {
			this.autoConfigurationClasses = autoConfigurationClasses;
			this.start = start;
			this.end = end;
			this.autoConfigurationMetadata = autoConfigurationMetadata;
			this.beanClassLoader = beanClassLoader;
		}

		@Override
		public ConditionOutcome[] resolveOutcomes() {
			return getOutcomes(this.autoConfigurationClasses, this.start, this.end, this.autoConfigurationMetadata);
		}

		private ConditionOutcome[] getOutcomes(String[] autoConfigurationClasses, int start, int end,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			ConditionOutcome[] outcomes = new ConditionOutcome[end - start];
			for (int i = start; i < end; i++) {
				String autoConfigurationClass = autoConfigurationClasses[i];
				if (autoConfigurationClass != null) {
					// <1> 获得指定自动配置类的 @ConditionalOnClass 注解的要求类
					String candidates = autoConfigurationMetadata.get(autoConfigurationClass, "ConditionalOnClass");
					if (candidates != null) {
						// 执行匹配
						outcomes[i - start] = getOutcome(candidates);
					}
				}
			}
			return outcomes;
		}

		private ConditionOutcome getOutcome(String candidates) {
			try {
				// 如果没有 , ，说明只有一个，直接匹配即可
				if (!candidates.contains(",")) {
					return getOutcome(candidates, this.beanClassLoader);
				}
				// 如果有 , ，说明有多个，逐个匹配
				for (String candidate : StringUtils.commaDelimitedListToStringArray(candidates)) {
					ConditionOutcome outcome = getOutcome(candidate, this.beanClassLoader);
					if (outcome != null) {
						return outcome;
					}
				}
			}
			catch (Exception ex) {
				// We'll get another chance later
			}
			return null;
		}

		private ConditionOutcome getOutcome(String className, ClassLoader classLoader) {
			// 通过使用 ClassNameFilter.MISSING 来，进行匹配类是否不存在。
			if (ClassNameFilter.MISSING.matches(className, classLoader)) {
				return ConditionOutcome.noMatch(ConditionMessage.forCondition(ConditionalOnClass.class)
						.didNotFind("required class").items(Style.QUOTE, className));
			}
			return null;
		}

	}

}
