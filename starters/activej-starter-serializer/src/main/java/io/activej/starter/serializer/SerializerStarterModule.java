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

package io.activej.starter.serializer;

import io.activej.common.builder.AbstractBuilder;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.serializer.CompatibilityLevel;
import io.activej.serializer.SerializerFactory;

/**
 * Starter module for high-performance binary serialization using {@link SerializerFactory}.
 * <p>
 * Provides a {@link SerializerFactory} with default settings. The compatibility level
 * can be customized via the builder to ensure backwards compatibility with previous
 * versions of serializers.
 *
 * <pre>{@code
 * // Default usage (LEVEL_4)
 * Module module = SerializerStarterModule.create();
 *
 * // Custom compatibility level
 * Module module = SerializerStarterModule.builder()
 *     .withCompatibilityLevel(CompatibilityLevel.LEVEL_4_LE)
 *     .build();
 * }</pre>
 */
public final class SerializerStarterModule extends AbstractModule {

	private CompatibilityLevel compatibilityLevel = CompatibilityLevel.LEVEL_4;

	private SerializerStarterModule() {
	}

	public static SerializerStarterModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new SerializerStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, SerializerStarterModule> {
		private Builder() {}

		public Builder withCompatibilityLevel(CompatibilityLevel compatibilityLevel) {
			checkNotBuilt(this);
			SerializerStarterModule.this.compatibilityLevel = compatibilityLevel;
			return this;
		}

		@Override
		protected SerializerStarterModule doBuild() {
			return SerializerStarterModule.this;
		}
	}

	@Provides
	SerializerFactory serializerFactory() {
		return SerializerFactory.builder()
			.withCompatibilityLevel(compatibilityLevel)
			.build();
	}
}
