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

package io.activej.http3;

import io.activej.common.MemSize;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;

/**
 * Defaults asserted against data-model.md §5.1. {@code ApplicationSettings} resolution order
 * (namespaced then short form) is exercised once here; every other module already covers the
 * resolution mechanism itself ({@code ApplicationSettings} is a {@code util-common} concern).
 */
public class Http3SettingsTest {
	@Test
	public void defaults() {
		Http3Settings settings = Http3Settings.create();

		assertEquals(MemSize.kilobytes(64).toLong(), settings.maxFieldSectionSize());
		assertEquals(MemSize.megabytes(100).toLong(), settings.maxBodySize());
		assertEquals(MemSize.kilobytes(16).toLong(), settings.maxControlFrameSize());
		assertEquals(100, settings.maxConcurrentRequestStreams());
		assertEquals(3, settings.maxUniStreams());
		assertEquals(256, settings.maxConnections());
		assertEquals(100, settings.maxQueuedRequests());
		assertEquals(Duration.ofSeconds(60).toMillis(), settings.requestTimeoutMillis());
	}

	@Test
	public void builderOverridesEveryTunableField() {
		Http3Settings settings = Http3Settings.builder()
			.withMaxFieldSectionSize(MemSize.kilobytes(32))
			.withMaxBodySize(MemSize.megabytes(10))
			.withMaxControlFrameSize(MemSize.kilobytes(8))
			.withMaxConcurrentRequestStreams(50)
			.withMaxConnections(64)
			.withMaxQueuedRequests(10)
			.withRequestTimeout(Duration.ofSeconds(5))
			.build();

		assertEquals(MemSize.kilobytes(32).toLong(), settings.maxFieldSectionSize());
		assertEquals(MemSize.megabytes(10).toLong(), settings.maxBodySize());
		assertEquals(MemSize.kilobytes(8).toLong(), settings.maxControlFrameSize());
		assertEquals(50, settings.maxConcurrentRequestStreams());
		assertEquals(64, settings.maxConnections());
		assertEquals(10, settings.maxQueuedRequests());
		assertEquals(Duration.ofSeconds(5).toMillis(), settings.requestTimeoutMillis());
		// maxUniStreams is fixed at 3 (FR-017): not a builder field, no withMaxUniStreams(...) exists.
		assertEquals(3, settings.maxUniStreams());
	}

	@Test
	public void fixedQpackAdvertisement() {
		Http3Settings settings = Http3Settings.create();
		assertEquals(0, settings.qpackMaxTableCapacity());
		assertEquals(0, settings.qpackBlockedStreams());
	}
}
