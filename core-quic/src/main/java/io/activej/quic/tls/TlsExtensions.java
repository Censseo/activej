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
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes {@link TlsExtension}s (RFC 8446 §4.2) on a {@link ByteBuf}: 2-byte type +
 * 2-byte length framing per extension.
 * <p>
 * Every declared length is checked against the remaining bytes before any allocation. Each
 * body is parsed from a bounded slice holding exactly the declared bytes — no per-body copy —
 * so a malformed body can never overrun into the next extension; bytes left over after a body
 * parser finishes are rejected. Unknown and GREASE types parse to {@link UnknownExtension}
 * (tolerated, never echoed — RFC 8701). The caller keeps the input buffer (mirrors
 * {@code QuicFrames.read}).
 */
public final class TlsExtensions {

	private TlsExtensions() {
	}

	public static void write(ByteBuf out, TlsExtension extension) {
		extension.writeTo(out);
	}

	public static int encodedLength(TlsExtension extension) {
		return extension.encodedLength();
	}

	/**
	 * Reads exactly one extension, consuming its type, length and body bytes.
	 *
	 * @throws TruncatedDataException if the header is truncated or a body field runs past the
	 * declared body length
	 * @throws MalformedDataException if the declared body length exceeds the remaining bytes,
	 * or a parsed body leaves trailing bytes
	 */
	public static TlsExtension read(ByteBuf buf) throws TruncatedDataException, MalformedDataException {
		requireRemaining(buf, 4, "extension header");
		int type = readShort(buf);
		int bodyLength = readShort(buf);
		if (bodyLength > buf.readRemaining()) {
			throw new MalformedDataException(
				"Extension 0x" + Integer.toHexString(type) + " declares " + bodyLength +
				" body bytes with " + buf.readRemaining() + " remaining");
		}
		ByteBuf body = buf.slice(bodyLength);
		try {
			TlsExtension extension = switch (type) {
				case SupportedVersionsExt.TYPE -> readSupportedVersions(body);
				case KeyShareExt.TYPE -> readKeyShare(body);
				case SupportedGroupsExt.TYPE -> readSupportedGroups(body);
				case SignatureAlgorithmsExt.TYPE -> readSignatureAlgorithms(body);
				case AlpnExt.TYPE -> readAlpn(body);
				case ServerNameExt.TYPE -> readServerName(body);
				case PskKeyExchangeModesExt.TYPE -> readPskKeyExchangeModes(body);
				case QuicTransportParametersExt.TYPE -> new QuicTransportParametersExt(QuicTransportParameters.read(body));
				default -> {
					// the opaque body is carried as-is — the one place the bytes are copied out
					byte[] bodyBytes = new byte[bodyLength];
					body.read(bodyBytes);
					yield new UnknownExtension(type, bodyBytes);
				}
			};
			if (body.canRead()) {
				throw new MalformedDataException(
					"Extension 0x" + Integer.toHexString(type) + " body has " + body.readRemaining() + " trailing bytes");
			}
			return extension;
		} finally {
			body.recycle();
			buf.moveHead(bodyLength);
		}
	}

	/**
	 * Reads extensions until {@code buf} is exhausted (the extensions vector of a message body).
	 * RFC 8446 §4.2: a given extension block MUST NOT carry two extensions of the same type —
	 * a duplicate aborts with {@code illegal_parameter}.
	 */
	public static List<TlsExtension> readList(ByteBuf buf) throws TruncatedDataException, MalformedDataException, TlsAlertException {
		List<TlsExtension> extensions = new ArrayList<>();
		Set<Integer> seenTypes = new HashSet<>();
		while (buf.canRead()) {
			TlsExtension extension = read(buf);
			if (!seenTypes.add(extension.type())) {
				throw new TlsAlertException(TlsAlerts.ILLEGAL_PARAMETER,
					"Duplicate extension type 0x" + Integer.toHexString(extension.type()) +
					" in one extension block (RFC 8446 §4.2)");
			}
			extensions.add(extension);
		}
		return extensions;
	}

	/** Writes a 2-byte-length-prefixed extensions vector (RFC 8446 §4.2). */
	static void writeList(ByteBuf buf, List<TlsExtension> extensions) {
		writeShort(buf, encodedListLength(extensions));
		for (TlsExtension extension : extensions) {
			extension.writeTo(buf);
		}
	}

	/** Exact encoded length of an extensions vector's content, excluding the 2-byte vector length. */
	static int encodedListLength(List<TlsExtension> extensions) {
		int length = 0;
		for (TlsExtension extension : extensions) {
			length += extension.encodedLength();
		}
		return length;
	}

	// ---- body parsers (operate on a bounded region holding exactly the declared body) ----

	private static TlsExtension readSupportedVersions(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		requireRemaining(body, 1, "supported_versions body");
		if (body.readRemaining() == 2) {
			// ServerHello selected-version form: a bare 2-byte version (RFC 8446 §4.2.1).
			return SupportedVersionsExt.ofSelectedVersion(readShort(body));
		}
		int listLength = body.readByte() & 0xFF;
		if ((listLength & 1) != 0 || listLength == 0) {
			throw new MalformedDataException("supported_versions list length must be positive and even: " + listLength);
		}
		if (listLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"supported_versions list declares " + listLength + " bytes with " + body.readRemaining() + " remaining");
		}
		int[] versions = new int[listLength / 2];
		for (int i = 0; i < versions.length; i++) {
			versions[i] = readShort(body);
		}
		return SupportedVersionsExt.ofClientVersions(versions);
	}

	private static TlsExtension readKeyShare(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		requireRemaining(body, 2, "key_share body");
		// ServerHello form is a single entry; ClientHello form starts with a 2-byte list length
		// covering the rest of the body.
		int firstShort = peekShort(body);
		if (firstShort == body.readRemaining() - 2) {
			body.moveHead(2); // client_shares list length
			List<KeyShareEntry> shares = new ArrayList<>();
			while (body.canRead()) {
				shares.add(readKeyShareEntry(body));
			}
			if (shares.isEmpty()) {
				throw new MalformedDataException("key_share client_shares list must not be empty");
			}
			return KeyShareExt.ofClientShares(shares);
		}
		return KeyShareExt.ofSelectedShare(readKeyShareEntry(body));
	}

	private static KeyShareEntry readKeyShareEntry(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		requireRemaining(body, 4, "key_share entry");
		int groupCode = readShort(body);
		int keyLength = readShort(body);
		if (keyLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"key_share key_exchange declares " + keyLength + " bytes with " + body.readRemaining() + " remaining");
		}
		NamedGroup group = NamedGroup.of(groupCode);
		if (group != null && keyLength != keyExchangeLength(group)) {
			throw new MalformedDataException(
				"key_exchange for group " + group + " must be " + keyExchangeLength(group) + " bytes: " + keyLength);
		}
		byte[] keyExchange = new byte[keyLength];
		body.read(keyExchange);
		return new KeyShareEntry(groupCode, keyExchange);
	}

	private static TlsExtension readSupportedGroups(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		return new SupportedGroupsExt(readUint16List(body, "supported_groups"));
	}

	private static TlsExtension readSignatureAlgorithms(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		return new SignatureAlgorithmsExt(readUint16List(body, "signature_algorithms"));
	}

	private static int[] readUint16List(ByteBuf body, String name) throws TruncatedDataException, MalformedDataException {
		requireRemaining(body, 2, name + " body");
		int listLength = readShort(body);
		if ((listLength & 1) != 0 || listLength == 0) {
			throw new MalformedDataException(name + " list length must be positive and even: " + listLength);
		}
		if (listLength > body.readRemaining()) {
			throw new TruncatedDataException(
				name + " list declares " + listLength + " bytes with " + body.readRemaining() + " remaining");
		}
		int[] codes = new int[listLength / 2];
		for (int i = 0; i < codes.length; i++) {
			codes[i] = readShort(body);
		}
		return codes;
	}

	private static TlsExtension readAlpn(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		requireRemaining(body, 2, "ALPN body");
		int listLength = readShort(body);
		if (listLength != body.readRemaining()) {
			throw new MalformedDataException(
				"ALPN protocol list declares " + listLength + " bytes with " + body.readRemaining() + " remaining");
		}
		List<String> protocols = new ArrayList<>();
		while (body.canRead()) {
			int protocolLength = body.readByte() & 0xFF;
			if (protocolLength == 0) {
				throw new MalformedDataException("ALPN protocol id must not be empty");
			}
			if (protocolLength > body.readRemaining()) {
				throw new TruncatedDataException(
					"ALPN protocol id declares " + protocolLength + " bytes with " + body.readRemaining() + " remaining");
			}
			byte[] bytes = new byte[protocolLength];
			body.read(bytes);
			protocols.add(new String(bytes, StandardCharsets.US_ASCII));
		}
		if (protocols.isEmpty()) {
			throw new MalformedDataException("ALPN protocol list must not be empty");
		}
		return new AlpnExt(protocols);
	}

	private static TlsExtension readServerName(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		if (body.readRemaining() == 0) {
			// Empty-body form: the server's SNI acknowledgement in EncryptedExtensions (RFC 6066 §3).
			return new ServerNameExt(null);
		}
		requireRemaining(body, 2, "server_name body");
		int listLength = readShort(body);
		if (listLength != body.readRemaining()) {
			throw new MalformedDataException(
				"server_name list declares " + listLength + " bytes with " + body.readRemaining() + " remaining");
		}
		String hostName = null;
		while (body.canRead()) {
			requireRemaining(body, 3, "server_name entry");
			int nameType = body.readByte() & 0xFF;
			int nameLength = readShort(body);
			if (nameLength > body.readRemaining()) {
				throw new TruncatedDataException(
					"server_name entry declares " + nameLength + " bytes with " + body.readRemaining() + " remaining");
			}
			byte[] bytes = new byte[nameLength];
			body.read(bytes);
			if (nameType == ServerNameExt.HOST_NAME_TYPE && hostName == null) {
				hostName = new String(bytes, StandardCharsets.US_ASCII);
			}
			// other name types (and a repeated host_name) are parsed and ignored (RFC 6066 §3)
		}
		return new ServerNameExt(hostName);
	}

	private static TlsExtension readPskKeyExchangeModes(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		requireRemaining(body, 1, "psk_key_exchange_modes body");
		int listLength = body.readByte() & 0xFF;
		if (listLength == 0) {
			throw new MalformedDataException("psk_key_exchange_modes list must not be empty");
		}
		if (listLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"psk_key_exchange_modes list declares " + listLength + " bytes with " + body.readRemaining() + " remaining");
		}
		int[] modes = new int[listLength];
		for (int i = 0; i < modes.length; i++) {
			modes[i] = body.readByte() & 0xFF;
		}
		return new PskKeyExchangeModesExt(modes);
	}

	// ---- scalar helpers (big-endian, matching the TLS wire format) ----

	static void writeShort(ByteBuf buf, int v) {
		buf.writeByte((byte) (v >>> 8));
		buf.writeByte((byte) v);
	}

	static int readShort(ByteBuf buf) {
		return ((buf.readByte() & 0xFF) << 8) | (buf.readByte() & 0xFF);
	}

	static int peekShort(ByteBuf buf) {
		return ((buf.peek(0) & 0xFF) << 8) | (buf.peek(1) & 0xFF);
	}

	private static void requireRemaining(ByteBuf buf, int n, String what) throws TruncatedDataException {
		if (buf.readRemaining() < n) {
			throw new TruncatedDataException("Truncated " + what + ": expected " + n + " more byte(s), only " + buf.readRemaining() + " remain");
		}
	}

	private static int keyExchangeLength(NamedGroup group) {
		return switch (group) {
			case X25519 -> 32;          // RFC 8446 §4.2.8.1
			case SECP256R1 -> 65;       // uncompressed point (RFC 8446 §4.2.8.2)
		};
	}
}
