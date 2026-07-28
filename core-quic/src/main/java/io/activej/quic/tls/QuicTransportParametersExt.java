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

package io.activej.quic.tls;

import io.activej.bytebuf.ByteBuf;

/**
 * The {@code quic_transport_parameters} extension (RFC 9001 §8.2), carrying the peer's RFC 9000
 * §18 transport parameters. Mandatory in both directions of a QUIC handshake; its absence is a
 * {@code missing_extension} alert at the engines.
 */
public final class QuicTransportParametersExt extends TlsExtension {
	public static final int TYPE = 0x0039;

	public final QuicTransportParameters parameters;

	public QuicTransportParametersExt(QuicTransportParameters parameters) {
		this.parameters = parameters;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + parameters.encodedLength();
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		parameters.writeTo(buf);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof QuicTransportParametersExt other)) return false;
		return parameters.equals(other.parameters);
	}

	@Override
	public int hashCode() {
		return parameters.hashCode();
	}
}
