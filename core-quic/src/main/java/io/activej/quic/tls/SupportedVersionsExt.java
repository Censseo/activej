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

import java.util.Arrays;

/**
 * The {@code supported_versions} extension (RFC 8446 §4.2.1), in both wire forms: the
 * ClientHello list form ({@code selectedForm == false}, one or more versions) and the
 * ServerHello selected-version form ({@code selectedForm == true}, exactly one version).
 * <p>
 * The QUIC profile negotiates TLS 1.3 only (RFC 9001 §8.1); peers offering anything else are
 * rejected with {@code protocol_version} by the engines.
 */
public final class SupportedVersionsExt extends TlsExtension {
	public static final int TYPE = 0x002b;

	/** TLS 1.3 (RFC 8446 §4.2.1). */
	public static final int TLS_1_3 = 0x0304;

	/** {@code true} for the ServerHello selected-version form, {@code false} for the ClientHello list form. */
	public final boolean selectedForm;

	/** Offered versions (list form) or the single selected version (selected form). */
	public final int[] versions;

	private SupportedVersionsExt(boolean selectedForm, int[] versions) {
		this.selectedForm = selectedForm;
		this.versions = versions.clone();
	}

	/** ClientHello list form (RFC 8446 §4.2.1). */
	public static SupportedVersionsExt ofClientVersions(int... versions) {
		if (versions.length == 0 || versions.length > 127) {
			throw new IllegalArgumentException("supported_versions must offer 1..127 versions: " + versions.length);
		}
		return new SupportedVersionsExt(false, versions);
	}

	/** ServerHello selected-version form (RFC 8446 §4.2.1). */
	public static SupportedVersionsExt ofSelectedVersion(int version) {
		return new SupportedVersionsExt(true, new int[] {version});
	}

	/** Defensive copy of {@link #versions}. */
	public int[] versions() {
		return versions.clone();
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + (selectedForm ? 2 : 1 + versions.length * 2);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		if (selectedForm) {
			TlsExtensions.writeShort(buf, versions[0]);
		} else {
			buf.writeByte((byte) (versions.length * 2));
			for (int version : versions) {
				TlsExtensions.writeShort(buf, version);
			}
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof SupportedVersionsExt other)) return false;
		return selectedForm == other.selectedForm && Arrays.equals(versions, other.versions);
	}

	@Override
	public int hashCode() {
		return 31 * Boolean.hashCode(selectedForm) + Arrays.hashCode(versions);
	}
}
