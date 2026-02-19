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

package io.activej.starter;

import io.activej.config.Config;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.inject.annotation.Provides;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.reactor.nio.NioReactor;

import static io.activej.launchers.initializers.Initializers.ofEventloop;

/**
 * A module that provides a {@link NioReactor} configured from {@link Config}.
 * <p>
 * The reactor is created as an {@link Eventloop} with configuration read from the
 * {@code "eventloop"} config subtree, and an optional {@link ThrottlingController} inspector.
 */
public final class ReactorModule extends AbstractModule {

	private ReactorModule() {
	}

	public static ReactorModule create() {
		return new ReactorModule();
	}

	@Provides
	NioReactor reactor(Config config, OptionalDependency<ThrottlingController> throttlingController) {
		return Eventloop.builder()
			.initialize(ofEventloop(config.getChild("eventloop")))
			.withInspector(throttlingController.orElse(null))
			.build();
	}
}
