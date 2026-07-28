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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code signature_algorithms} extension (RFC 8446 §4.2.3). Scheme codepoints are carried
 * raw so that unknown/GREASE values (including legacy PKCS#1 v1.5 codes a peer may offer) are
 * tolerated; {@link #knownSchemes()} filters to the QUIC profile schemes. PKCS#1 v1.5 is never
 * selected for CertificateVerify (RFC 8446 §4.2.3).
 */
public final class SignatureAlgorithmsExt extends TlsExtension {
	public static final int TYPE = 0x000d;

	public final int[] schemeCodes;

	public SignatureAlgorithmsExt(int... schemeCodes) {
		if (schemeCodes.length == 0) {
			throw new IllegalArgumentException("signature_algorithms must not be empty");
		}
		this.schemeCodes = schemeCodes.clone();
	}

	/** Defensive copy of {@link #schemeCodes}. */
	public int[] schemeCodes() {
		return schemeCodes.clone();
	}

	/** The offered schemes that belong to the QUIC profile, in wire order; unknown codes are dropped. */
	public List<SignatureScheme> knownSchemes() {
		List<SignatureScheme> schemes = new ArrayList<>(schemeCodes.length);
		for (int code : schemeCodes) {
			SignatureScheme scheme = SignatureScheme.of(code);
			if (scheme != null) schemes.add(scheme);
		}
		return schemes;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + 2 + schemeCodes.length * 2;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		TlsExtensions.writeShort(buf, schemeCodes.length * 2);
		for (int code : schemeCodes) {
			TlsExtensions.writeShort(buf, code);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof SignatureAlgorithmsExt other)) return false;
		return Arrays.equals(schemeCodes, other.schemeCodes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(schemeCodes);
	}
}
