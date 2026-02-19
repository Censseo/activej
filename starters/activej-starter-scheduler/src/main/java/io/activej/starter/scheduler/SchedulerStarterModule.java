/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.starter.scheduler;

import io.activej.async.function.AsyncSupplier;
import io.activej.async.service.TaskScheduler;
import io.activej.async.service.TaskScheduler.Schedule;
import io.activej.common.builder.AbstractBuilder;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.promise.RetryPolicy;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A starter module that allows registration of named periodic tasks
 * as {@link TaskScheduler} instances.
 * <p>
 * Each task is defined with a name, an {@link AsyncSupplier}, a {@link Schedule},
 * and an optional {@link RetryPolicy}. All registered tasks are provided
 * as a {@code Set<TaskScheduler>} via {@code @ProvidesIntoSet}.
 * <p>
 * By default, {@link #create()} returns an empty scheduler module.
 * Use the {@link Builder} to register tasks.
 */
public final class SchedulerStarterModule extends AbstractModule {

	private final List<TaskDefinition> taskDefinitions = new ArrayList<>();

	private record TaskDefinition(
		String name,
		AsyncSupplier<?> task,
		Schedule schedule,
		@Nullable RetryPolicy<?> retryPolicy
	) {}

	private SchedulerStarterModule() {
	}

	/**
	 * Creates an empty scheduler module with no registered tasks.
	 */
	public static SchedulerStarterModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new SchedulerStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, SchedulerStarterModule> {
		private Builder() {}

		/**
		 * Registers a named task with the given schedule.
		 *
		 * @param name     the name of the task
		 * @param task     the async supplier representing the task logic
		 * @param schedule the schedule defining when the task runs
		 */
		public Builder withTask(String name, AsyncSupplier<?> task, Schedule schedule) {
			checkNotBuilt(this);
			SchedulerStarterModule.this.taskDefinitions.add(new TaskDefinition(name, task, schedule, null));
			return this;
		}

		/**
		 * Registers a named task with the given schedule and retry policy.
		 *
		 * @param name        the name of the task
		 * @param task        the async supplier representing the task logic
		 * @param schedule    the schedule defining when the task runs
		 * @param retryPolicy the retry policy to apply on task failure
		 */
		public Builder withTask(String name, AsyncSupplier<?> task, Schedule schedule, RetryPolicy<?> retryPolicy) {
			checkNotBuilt(this);
			SchedulerStarterModule.this.taskDefinitions.add(new TaskDefinition(name, task, schedule, retryPolicy));
			return this;
		}

		@Override
		protected SchedulerStarterModule doBuild() {
			return SchedulerStarterModule.this;
		}
	}

	@ProvidesIntoSet
	TaskScheduler taskSchedulers(Reactor reactor) {
		if (taskDefinitions.isEmpty()) {
			// Provide a no-op disabled scheduler when no tasks are registered
			return TaskScheduler.builder(reactor, () -> null)
				.withSchedule(Schedule.immediate())
				.withEnabled(false)
				.build();
		}

		// When there are multiple task definitions, we install each as a separate module
		// For the @ProvidesIntoSet approach, we create the first one here
		TaskDefinition first = taskDefinitions.getFirst();
		TaskScheduler.Builder builder = TaskScheduler.builder(reactor, first.task)
			.withSchedule(first.schedule);
		if (first.retryPolicy != null) {
			builder.withRetryPolicy(first.retryPolicy);
		}
		return builder.build();
	}

	@Override
	protected void configure() {
		// For additional tasks beyond the first, install sub-modules
		for (int i = 1; i < taskDefinitions.size(); i++) {
			TaskDefinition def = taskDefinitions.get(i);
			install(createTaskModule(def));
		}
	}

	private static AbstractModule createTaskModule(TaskDefinition def) {
		return new AbstractModule() {
			@ProvidesIntoSet
			TaskScheduler taskScheduler(Reactor reactor) {
				TaskScheduler.Builder builder = TaskScheduler.builder(reactor, def.task)
					.withSchedule(def.schedule);
				if (def.retryPolicy != null) {
					builder.withRetryPolicy(def.retryPolicy);
				}
				return builder.build();
			}
		};
	}
}
