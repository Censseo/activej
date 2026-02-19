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

package io.activej.starter.etl;

import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;

/**
 * ETL starter module for ActiveJ applications.
 * <p>
 * TODO: Add pre-configured ETL bindings with sensible defaults.
 */
public final class EtlStarterModule extends AbstractModule {
	private EtlStarterModule() {
	}

	public static Module create() {
		// TODO: Add ETL-specific bindings and configuration
		return Module.empty();
	}

	public static Builder builder() {
		return new EtlStarterModule().new Builder();
	}

	public final class Builder extends io.activej.common.builder.AbstractBuilder<Builder, EtlStarterModule> {
		private Builder() {}

		@Override
		protected EtlStarterModule doBuild() {
			return EtlStarterModule.this;
		}
	}
}
