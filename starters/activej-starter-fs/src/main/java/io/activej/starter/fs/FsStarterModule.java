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

package io.activej.starter.fs;

import io.activej.common.builder.AbstractBuilder;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.config.converter.ConfigConverters;
import io.activej.fs.FileSystem;
import io.activej.fs.IFileSystem;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.reactor.Reactor;
import io.activej.service.ServiceGraphModule;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executor;

import static io.activej.config.converter.ConfigConverters.ofPath;

/**
 * Starter module for local {@link FileSystem} operations.
 * <p>
 * Provides a pre-configured {@link IFileSystem} backed by a local directory,
 * an {@link Executor} for blocking FS operations, and installs
 * {@link ServiceGraphModule} and {@link ConfigModule} for lifecycle management.
 * <p>
 * The root storage path is read from the {@code "fs.path"} config property,
 * defaulting to {@code "./storage"}.
 *
 * <pre>{@code
 * // Default usage
 * Module module = FsStarterModule.create();
 *
 * // Custom path
 * Module module = FsStarterModule.builder()
 *     .withPath(Paths.get("/data/files"))
 *     .build();
 * }</pre>
 */
public final class FsStarterModule extends AbstractModule {
	public static final Path DEFAULT_PATH = Paths.get("./storage");

	private Path path = DEFAULT_PATH;

	private FsStarterModule() {
	}

	public static FsStarterModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new FsStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, FsStarterModule> {
		private Builder() {}

		public Builder withPath(Path path) {
			checkNotBuilt(this);
			FsStarterModule.this.path = path;
			return this;
		}

		@Override
		protected FsStarterModule doBuild() {
			return FsStarterModule.this;
		}
	}

	@Override
	protected void configure() {
		install(ServiceGraphModule.create());
		install(ConfigModule.builder()
			.withEffectiveConfigLogger()
			.build());
	}

	@Provides
	IFileSystem fileSystem(Reactor reactor, Executor executor, Config config) {
		Path storagePath = config.get(ofPath(), "fs.path", path);
		return FileSystem.create(reactor, executor, storagePath);
	}

	@Provides
	Executor executor(Config config) {
		return ConfigConverters.getExecutor(config.getChild("fs.executor"));
	}
}
