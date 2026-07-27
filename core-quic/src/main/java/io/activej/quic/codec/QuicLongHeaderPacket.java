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

package io.activej.quic.codec;

import io.activej.quic.QuicConnectionId;

/**
 * Shared shape of every long-header packet (RFC 9000 §17.2): a version and two connection IDs.
 */
public abstract class QuicLongHeaderPacket extends QuicPacket {
	/** Unsigned 32-bit wire version, held in a {@code long} to avoid sign issues. */
	public final long version;
	public final QuicConnectionId destinationConnectionId;
	public final QuicConnectionId sourceConnectionId;

	QuicLongHeaderPacket(long version, QuicConnectionId destinationConnectionId, QuicConnectionId sourceConnectionId) {
		this.version = version;
		this.destinationConnectionId = destinationConnectionId;
		this.sourceConnectionId = sourceConnectionId;
	}

	@Override
	public final QuicConnectionId destinationConnectionId() {
		return destinationConnectionId;
	}
}
