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

package io.activej.quic.crypto;

/**
 * The client/server {@link QuicKeys} pair for the Initial encryption level (RFC 9001 §5.2),
 * as returned by {@link QuicKeys#initial}.
 */
public final class InitialKeys {
	private final QuicKeys client;
	private final QuicKeys server;

	public InitialKeys(QuicKeys client, QuicKeys server) {
		this.client = client;
		this.server = server;
	}

	public QuicKeys client() {
		return client;
	}

	public QuicKeys server() {
		return server;
	}
}
