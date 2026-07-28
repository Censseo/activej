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
 * The {@code supported_groups} extension (RFC 8446 §4.2.7). Group codepoints are carried raw so
 * that unknown/GREASE values are tolerated; {@link #knownGroups()} filters to the QUIC profile
 * groups ({@code x25519}, {@code secp256r1}).
 */
public final class SupportedGroupsExt extends TlsExtension {
	public static final int TYPE = 0x000a;

	public final int[] groupCodes;

	public SupportedGroupsExt(int... groupCodes) {
		if (groupCodes.length == 0) {
			throw new IllegalArgumentException("supported_groups must not be empty");
		}
		this.groupCodes = groupCodes.clone();
	}

	/** Defensive copy of {@link #groupCodes}. */
	public int[] groupCodes() {
		return groupCodes.clone();
	}

	/** The offered groups that belong to the QUIC profile, in wire order; unknown/GREASE codes are dropped. */
	public List<NamedGroup> knownGroups() {
		List<NamedGroup> groups = new ArrayList<>(groupCodes.length);
		for (int code : groupCodes) {
			NamedGroup group = NamedGroup.of(code);
			if (group != null) groups.add(group);
		}
		return groups;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + 2 + groupCodes.length * 2;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		TlsExtensions.writeShort(buf, groupCodes.length * 2);
		for (int code : groupCodes) {
			TlsExtensions.writeShort(buf, code);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof SupportedGroupsExt other)) return false;
		return Arrays.equals(groupCodes, other.groupCodes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(groupCodes);
	}
}
