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

package io.activej.starter.monitoring;

import io.activej.common.builder.AbstractBuilder;
import io.activej.common.initializer.Initializer;
import io.activej.config.Config;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.jmx.JmxModule;
import io.activej.trigger.TriggersModule;
import io.activej.trigger.TriggersModuleSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.activej.launchers.initializers.Initializers.*;

/**
 * A starter module that provides pre-configured monitoring support
 * with JMX and Triggers for ActiveJ applications.
 * <p>
 * By default, {@link #create()} installs both {@link JmxModule} and {@link TriggersModule}.
 * Use the {@link Builder} to customize which monitoring features are enabled.
 */
public final class MonitoringStarterModule extends AbstractModule {

	private boolean jmxEnabled;
	private boolean triggersEnabled;
	private boolean globalEventloopStats;
	private boolean businessLogicTriggers;
	private boolean throttlingTriggers;

	private MonitoringStarterModule() {
	}

	/**
	 * Creates a monitoring module with both JMX and Triggers enabled by default.
	 */
	public static MonitoringStarterModule create() {
		return builder()
			.withJmx()
			.withTriggers()
			.build();
	}

	public static Builder builder() {
		return new MonitoringStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, MonitoringStarterModule> {
		private Builder() {}

		/**
		 * Enables JMX monitoring support by installing {@link JmxModule}.
		 */
		public Builder withJmx() {
			checkNotBuilt(this);
			MonitoringStarterModule.this.jmxEnabled = true;
			return this;
		}

		/**
		 * Enables trigger-based monitoring by installing {@link TriggersModule}.
		 */
		public Builder withTriggers() {
			checkNotBuilt(this);
			MonitoringStarterModule.this.triggersEnabled = true;
			return this;
		}

		/**
		 * Enables global eventloop statistics as a JMX global MBean.
		 * Requires JMX to be enabled.
		 */
		public Builder withGlobalEventloopStats() {
			checkNotBuilt(this);
			MonitoringStarterModule.this.globalEventloopStats = true;
			return this;
		}

		/**
		 * Enables business logic triggers for eventloop monitoring.
		 * Requires Triggers to be enabled.
		 */
		public Builder withBusinessLogicTriggers() {
			checkNotBuilt(this);
			MonitoringStarterModule.this.businessLogicTriggers = true;
			return this;
		}

		/**
		 * Enables throttling controller triggers.
		 * Requires Triggers to be enabled.
		 */
		public Builder withThrottlingTriggers() {
			checkNotBuilt(this);
			MonitoringStarterModule.this.throttlingTriggers = true;
			return this;
		}

		@Override
		protected MonitoringStarterModule doBuild() {
			return MonitoringStarterModule.this;
		}
	}

	@Override
	protected void configure() {
		if (jmxEnabled) {
			install(JmxModule.create());
		}
		if (triggersEnabled) {
			install(TriggersModule.create());
		}
	}

	@ProvidesIntoSet
	Initializer<JmxModule.Builder> jmxInitializer() {
		if (!jmxEnabled || !globalEventloopStats) {
			return builder -> {};
		}
		return ofGlobalEventloopStats();
	}

	@ProvidesIntoSet
	Initializer<TriggersModuleSettings> triggersInitializer(OptionalDependency<Config> optionalConfig) {
		List<Initializer<TriggersModuleSettings>> initializers = new ArrayList<>();

		if (triggersEnabled) {
			initializers.add(ofEventloopFatalErrorsTriggers());
			initializers.add(ofLauncherTriggers(Duration.ofMinutes(1)));

			if (businessLogicTriggers && optionalConfig.isPresent()) {
				initializers.add(ofEventloopBusinessLogicTriggers(optionalConfig.get().getChild("monitoring")));
			}

			if (throttlingTriggers && optionalConfig.isPresent()) {
				initializers.add(ofThrottlingControllerTriggers(optionalConfig.get().getChild("monitoring")));
			}
		}

		return Initializer.combine(initializers);
	}
}
