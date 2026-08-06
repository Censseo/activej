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
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@code pre_shared_key} extension (RFC 8446 §4.2.11), in both of its forms:
 * <ul>
 *     <li>the <b>offer</b> a ClientHello carries — {@code identities<7..2^16-1>} paired one-to-one
 *     with {@code binders<33..2^16-1>};</li>
 *     <li>the <b>selection</b> a ServerHello carries — a bare {@code uint16 selected_identity}.</li>
 * </ul>
 * The two are told apart on the wire by body length, following the {@link SupportedVersionsExt}
 * precedent: an offer's body is at least 44 bytes (two vector lengths plus the RFC's own lower
 * bounds), so a 2-byte body can only be a selection.
 * <p>
 * <b>This extension must be written last in a ClientHello</b> (RFC 8446 §4.2.11), because the binder
 * is an HMAC over the message truncated immediately before the binders vector — see
 * {@link #bindersSectionLength()} and {@code TlsPskBinders}.
 * <p>
 * A PSK identity is a session ticket, and therefore <b>secret material</b>: it never appears in a log
 * line, an exception message or a {@code toString} (spec FR-050, SI-6).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.11">RFC 8446 §4.2.11</a>
 */
public final class PreSharedKeyExt extends TlsExtension {
	public static final int TYPE = 41; // 0x0029

	/** The smallest legal offer body: {@code identities<7..>} and {@code binders<33..>} plus their vector lengths. */
	static final int MIN_OFFER_BODY_LENGTH = 2 + 7 + 2 + 33;

	/**
	 * One offered identity: the opaque ticket ({@code identity<1..2^16-1>}) and the obfuscated ticket
	 * age (a {@code uint32} of milliseconds, RFC 8446 §4.2.11.1).
	 * <p>
	 * {@code identity} is <b>secret material</b>; it is cloned on the way in and on the way out, and
	 * deliberately absent from {@link #toString()}.
	 */
	public record PskIdentity(byte[] identity, long obfuscatedTicketAge) {
		public PskIdentity {
			Objects.requireNonNull(identity, "identity");
			if (identity.length == 0 || identity.length > 0xFFFF) {
				throw new IllegalArgumentException("PSK identity must be 1..65535 bytes: " + identity.length);
			}
			if ((obfuscatedTicketAge & ~0xFFFFFFFFL) != 0) {
				throw new IllegalArgumentException("obfuscated_ticket_age is a uint32: " + obfuscatedTicketAge);
			}
			identity = identity.clone();
		}

		@Override
		public byte[] identity() {
			return identity.clone();
		}

		int encodedLength() {
			return 2 + identity.length + 4;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof PskIdentity other &&
				obfuscatedTicketAge == other.obfuscatedTicketAge &&
				Arrays.equals(identity, other.identity);
		}

		@Override
		public int hashCode() {
			return 31 * Long.hashCode(obfuscatedTicketAge) + Arrays.hashCode(identity);
		}

		@Override
		public String toString() {
			return "PskIdentity[" + identity.length + " bytes]";
		}
	}

	/** The offered identities, or {@code null} in the ServerHello form. */
	public final @Nullable List<PskIdentity> identities;

	/** The offered binders, one per identity, or {@code null} in the ServerHello form. */
	public final @Nullable List<byte[]> binders;

	/** The identity the server selected, or {@code -1} in the ClientHello form. */
	public final int selectedIdentity;

	private PreSharedKeyExt(@Nullable List<PskIdentity> identities, @Nullable List<byte[]> binders, int selectedIdentity) {
		this.identities = identities;
		this.binders = binders;
		this.selectedIdentity = selectedIdentity;
	}

	/**
	 * The ClientHello form. The lists must be the same non-zero length (RFC 8446 §4.2.11), and each
	 * binder must be 32..255 bytes — the hash length of the suite its ticket was issued under.
	 */
	public static PreSharedKeyExt ofClientOffer(List<PskIdentity> identities, List<byte[]> binders) {
		Objects.requireNonNull(identities, "identities");
		Objects.requireNonNull(binders, "binders");
		if (identities.isEmpty()) {
			throw new IllegalArgumentException("pre_shared_key must offer at least one identity (RFC 8446 §4.2.11)");
		}
		if (identities.size() != binders.size()) {
			throw new IllegalArgumentException("pre_shared_key offers " + identities.size() + " identities against " +
				binders.size() + " binders (RFC 8446 §4.2.11)");
		}
		List<byte[]> copiedBinders = new java.util.ArrayList<>(binders.size());
		for (byte[] binder : binders) {
			Objects.requireNonNull(binder, "binder");
			if (binder.length < 32 || binder.length > 255) {
				throw new IllegalArgumentException("PskBinderEntry must be 32..255 bytes: " + binder.length);
			}
			copiedBinders.add(binder.clone());
		}
		return new PreSharedKeyExt(List.copyOf(identities), List.copyOf(copiedBinders), -1);
	}

	/** The ServerHello form: the index into the offered identities the server accepted. */
	public static PreSharedKeyExt ofSelectedIdentity(int selectedIdentity) {
		if (selectedIdentity < 0 || selectedIdentity > 0xFFFF) {
			throw new IllegalArgumentException("selected_identity is a uint16: " + selectedIdentity);
		}
		return new PreSharedKeyExt(null, null, selectedIdentity);
	}

	/** Whether this is the ClientHello offer form rather than the ServerHello selection form. */
	public boolean isOffer() {
		return identities != null;
	}

	/**
	 * The exact width of the trailing binders vector, including its 2-byte length — the number of
	 * bytes RFC 8446 §4.2.11.2 removes from the end of the ClientHello before hashing it for the
	 * binder computation.
	 *
	 * @throws IllegalStateException in the ServerHello form, which carries no binders
	 */
	public int bindersSectionLength() {
		List<byte[]> offered = binders;
		if (offered == null) {
			throw new IllegalStateException("A selected_identity pre_shared_key carries no binders");
		}
		int length = 2;
		for (byte[] binder : offered) {
			length += 1 + binder.length;
		}
		return length;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		if (!isOffer()) {
			return 4 + 2;
		}
		int length = 4 + 2;
		for (PskIdentity identity : identities) {
			length += identity.encodedLength();
		}
		return length + bindersSectionLength();
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		if (!isOffer()) {
			TlsExtensions.writeShort(buf, selectedIdentity);
			return;
		}
		int identitiesLength = 0;
		for (PskIdentity identity : identities) {
			identitiesLength += identity.encodedLength();
		}
		TlsExtensions.writeShort(buf, identitiesLength);
		for (PskIdentity identity : identities) {
			TlsExtensions.writeShort(buf, identity.identity.length);
			buf.put(identity.identity);
			TlsExtensions.writeShort(buf, (int) (identity.obfuscatedTicketAge >>> 16));
			TlsExtensions.writeShort(buf, (int) identity.obfuscatedTicketAge);
		}
		TlsExtensions.writeShort(buf, bindersSectionLength() - 2);
		for (byte[] binder : binders) {
			buf.writeByte((byte) binder.length);
			buf.put(binder);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof PreSharedKeyExt other)) return false;
		if (selectedIdentity != other.selectedIdentity) return false;
		if (!Objects.equals(identities, other.identities)) return false;
		if (binders == null || other.binders == null) return binders == other.binders;
		if (binders.size() != other.binders.size()) return false;
		for (int i = 0; i < binders.size(); i++) {
			if (!Arrays.equals(binders.get(i), other.binders.get(i))) return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int result = 31 * selectedIdentity + Objects.hashCode(identities);
		if (binders != null) {
			for (byte[] binder : binders) {
				result = 31 * result + Arrays.hashCode(binder);
			}
		}
		return result;
	}

	/** Never prints an identity or a binder (SI-6). */
	@Override
	public String toString() {
		return isOffer()
			? "PreSharedKeyExt[offer of " + identities.size() + "]"
			: "PreSharedKeyExt[selected " + selectedIdentity + ']';
	}
}
