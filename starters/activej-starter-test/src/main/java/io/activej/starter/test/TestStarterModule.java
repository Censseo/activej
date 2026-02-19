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

package io.activej.starter.test;

import io.activej.eventloop.Eventloop;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.reactor.nio.NioReactor;

/**
 * A simple starter module that provides an {@link Eventloop} as a {@link NioReactor}
 * for test contexts.
 */
public final class TestStarterModule extends AbstractModule {
	private TestStarterModule() {
	}

	public static TestStarterModule create() {
		return new TestStarterModule();
	}

	@Provides
	NioReactor reactor() {
		return Eventloop.create();
	}
}
