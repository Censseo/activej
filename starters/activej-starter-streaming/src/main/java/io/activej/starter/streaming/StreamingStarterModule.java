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

package io.activej.starter.streaming;

import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Starter module for reactive streaming with CSP channels and Datastream.
 * <p>
 * This module re-exports the {@code activej-csp} and {@code activej-datastream}
 * dependencies and provides a default {@link Executor} for blocking stream operations
 * (e.g. file I/O within a stream pipeline).
 *
 * <pre>{@code
 * Module module = StreamingStarterModule.create();
 * }</pre>
 */
public final class StreamingStarterModule extends AbstractModule {

	private StreamingStarterModule() {
	}

	public static StreamingStarterModule create() {
		return new StreamingStarterModule();
	}

	@Provides
	Executor executor() {
		return Executors.newCachedThreadPool();
	}
}
