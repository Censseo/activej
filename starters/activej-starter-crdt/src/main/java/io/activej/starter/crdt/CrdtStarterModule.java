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

package io.activej.starter.crdt;

import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;

/**
 * CRDT starter module for ActiveJ applications.
 * <p>
 * TODO: Add pre-configured CRDT bindings with sensible defaults.
 */
public final class CrdtStarterModule extends AbstractModule {
	private CrdtStarterModule() {
	}

	public static Module create() {
		// TODO: Add CRDT-specific bindings and configuration
		return Module.empty();
	}

	public static Builder builder() {
		return new CrdtStarterModule().new Builder();
	}

	public final class Builder extends io.activej.common.builder.AbstractBuilder<Builder, CrdtStarterModule> {
		private Builder() {}

		@Override
		protected CrdtStarterModule doBuild() {
			return CrdtStarterModule.this;
		}
	}
}
