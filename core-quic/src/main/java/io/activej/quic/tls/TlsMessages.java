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
import io.activej.common.ApplicationSettings;
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.tls.CertificateMessage.CertificateEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@link TlsHandshakeMessage}s (RFC 8446 §4) on a {@link ByteBuf}: 1-byte type
 * + 3-byte length framing per message.
 * <p>
 * FR-017 bounds, checked before any allocation: the declared 3-byte length must not exceed the
 * remaining bytes ({@link MalformedDataException}) and must not exceed
 * {@link #MAX_HANDSHAKE_MESSAGE_SIZE} ({@link TlsAlertException} with {@code decode_error} —
 * this is the configured-bound case of the spec's Error Scenarios table). Each body is then
 * parsed from a bounded slice holding exactly the declared bytes — no per-body copy — so a
 * malformed body can never overrun into the next message; bytes left over after a body parser
 * finishes are rejected. An unknown message type surfaces as {@link MalformedDataException} at
 * this raw layer — the engines own the {@code unexpected_message} alert mapping
 * (data-model.md). The caller keeps the input buffer (mirrors {@code QuicFrames.read}).
 */
public final class TlsMessages {

	/**
	 * FR-017: maximum declared size of one handshake message, default 1 MiB. Overridable via
	 * {@code -Dio.activej.quic.tls.TlsMessages.maxHandshakeMessageSize=…} (or
	 * {@code -DTlsMessages.maxHandshakeMessageSize=…}). CRYPTO bytes are not flow-controlled
	 * (RFC 9000 §4.1), so this bound is the only one — it must exist.
	 */
	public static final MemSize MAX_HANDSHAKE_MESSAGE_SIZE =
		ApplicationSettings.getMemSize(TlsMessages.class, "maxHandshakeMessageSize", MemSize.megabytes(1));

	private TlsMessages() {
	}

	public static void write(ByteBuf out, TlsHandshakeMessage message) {
		message.writeTo(out);
	}

	public static int encodedLength(TlsHandshakeMessage message) {
		return message.encodedLength();
	}

	/**
	 * Reads exactly one handshake message, consuming its type, length and body bytes.
	 *
	 * @throws TruncatedDataException if the header is truncated or a body field runs past the
	 * declared body length
	 * @throws MalformedDataException if the declared body length exceeds the remaining bytes,
	 * the message type is unknown, a field the RFC declares non-empty arrives zero-length
	 * ({@code certificate_data}, {@code ticket_nonce}, {@code ticket}, CertificateVerify
	 * {@code signature}), or a parsed body leaves trailing bytes
	 * @throws TlsAlertException with {@code decode_error} if the declared body length exceeds
	 * {@link #MAX_HANDSHAKE_MESSAGE_SIZE}
	 */
	public static TlsHandshakeMessage read(ByteBuf buf)
			throws TruncatedDataException, MalformedDataException, TlsAlertException {
		requireRemaining(buf, 4, "handshake message header");
		int type = buf.readByte() & 0xFF;
		int bodyLength = readUint24(buf);
		if (bodyLength > buf.readRemaining()) {
			throw new MalformedDataException(
				"Handshake message " + type + " declares " + bodyLength +
				" body bytes with " + buf.readRemaining() + " remaining");
		}
		if (bodyLength > MAX_HANDSHAKE_MESSAGE_SIZE.toInt()) {
			throw new TlsAlertException(TlsAlerts.DECODE_ERROR,
				"Handshake message " + type + " declares " + bodyLength + " body bytes, exceeding the configured " +
				"maxHandshakeMessageSize of " + MAX_HANDSHAKE_MESSAGE_SIZE);
		}
		ByteBuf body = buf.slice(bodyLength);
		try {
			TlsHandshakeMessage message = switch (type) {
				case ClientHelloMessage.TYPE -> readClientHello(body);
				case ServerHelloMessage.TYPE -> readServerHello(body);
				case NewSessionTicketMessage.TYPE -> readNewSessionTicket(body);
				case EncryptedExtensionsMessage.TYPE -> new EncryptedExtensionsMessage(readExtensionsVector(body, "encrypted_extensions"));
				case CertificateMessage.TYPE -> readCertificate(body);
				case CertificateVerifyMessage.TYPE -> readCertificateVerify(body);
				case FinishedMessage.TYPE -> readFinished(body);
				default -> throw new MalformedDataException("Unknown handshake message type: " + type);
			};
			if (body.canRead()) {
				throw new MalformedDataException(
					"Handshake message " + type + " body has " + body.readRemaining() + " trailing bytes");
			}
			return message;
		} finally {
			body.recycle();
			buf.moveHead(bodyLength);
		}
	}
	// ---- body parsers (operate on a bounded region holding exactly the declared body) ----

	private static TlsHandshakeMessage readClientHello(ByteBuf body) throws TruncatedDataException, MalformedDataException, TlsAlertException {
		requireRemaining(body, 2 + 32, "ClientHello fixed fields");
		int legacyVersion = readShort(body);
		byte[] random = new byte[32];
		body.read(random);
		requireRemaining(body, 1, "ClientHello legacy_session_id");
		int sessionIdLength = body.readByte() & 0xFF;
		if (sessionIdLength > 32) {
			throw new MalformedDataException("legacy_session_id must be at most 32 bytes: " + sessionIdLength);
		}
		if (sessionIdLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"legacy_session_id declares " + sessionIdLength + " bytes with " + body.readRemaining() + " remaining");
		}
		byte[] sessionId = new byte[sessionIdLength];
		body.read(sessionId);
		requireRemaining(body, 2, "ClientHello cipher_suites");
		int suitesLength = readShort(body);
		if ((suitesLength & 1) != 0 || suitesLength == 0) {
			throw new MalformedDataException("cipher_suites length must be positive and even: " + suitesLength);
		}
		if (suitesLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"cipher_suites declares " + suitesLength + " bytes with " + body.readRemaining() + " remaining");
		}
		int[] cipherSuites = new int[suitesLength / 2];
		for (int i = 0; i < cipherSuites.length; i++) {
			cipherSuites[i] = readShort(body);
		}
		requireRemaining(body, 1, "ClientHello legacy_compression_methods");
		int compressionsLength = body.readByte() & 0xFF;
		if (compressionsLength == 0) {
			throw new MalformedDataException("legacy_compression_methods must not be empty");
		}
		if (compressionsLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"legacy_compression_methods declares " + compressionsLength + " bytes with " + body.readRemaining() + " remaining");
		}
		int[] compressionMethods = new int[compressionsLength];
		for (int i = 0; i < compressionMethods.length; i++) {
			compressionMethods[i] = body.readByte() & 0xFF;
		}
		List<TlsExtension> extensions = readExtensionsVector(body, "ClientHello");
		return new ClientHelloMessage(legacyVersion, random, sessionId, cipherSuites, compressionMethods, extensions);
	}

	private static TlsHandshakeMessage readServerHello(ByteBuf body) throws TruncatedDataException, MalformedDataException, TlsAlertException {
		requireRemaining(body, 2 + 32 + 1 + 2 + 1, "ServerHello fixed fields");
		int legacyVersion = readShort(body);
		byte[] random = new byte[32];
		body.read(random);
		int sessionIdEchoLength = body.readByte() & 0xFF;
		if (sessionIdEchoLength > 32) {
			throw new MalformedDataException("session_id_echo must be at most 32 bytes: " + sessionIdEchoLength);
		}
		if (sessionIdEchoLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"session_id_echo declares " + sessionIdEchoLength + " bytes with " + body.readRemaining() + " remaining");
		}
		byte[] sessionIdEcho = new byte[sessionIdEchoLength];
		body.read(sessionIdEcho);
		requireRemaining(body, 2 + 1, "ServerHello cipher suite and compression");
		int cipherSuite = readShort(body);
		int compressionMethod = body.readByte() & 0xFF;
		List<TlsExtension> extensions = readExtensionsVector(body, "ServerHello");
		return new ServerHelloMessage(legacyVersion, random, sessionIdEcho, cipherSuite, compressionMethod, extensions);
	}

	private static TlsHandshakeMessage readNewSessionTicket(ByteBuf body) throws TruncatedDataException, MalformedDataException, TlsAlertException {
		requireRemaining(body, 4 + 4 + 1, "NewSessionTicket fixed fields");
		long ticketLifetime = readUint32(body);
		long ticketAgeAdd = readUint32(body);
		int nonceLength = body.readByte() & 0xFF;
		if (nonceLength == 0) {
			throw new MalformedDataException("ticket_nonce must be at least 1 byte (RFC 8446 §4.6.1)");
		}
		if (nonceLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"ticket_nonce declares " + nonceLength + " bytes with " + body.readRemaining() + " remaining");
		}
		byte[] nonce = new byte[nonceLength];
		body.read(nonce);
		requireRemaining(body, 2, "NewSessionTicket ticket");
		int ticketLength = readShort(body);
		if (ticketLength == 0) {
			throw new MalformedDataException("ticket must be at least 1 byte (RFC 8446 §4.6.1)");
		}
		if (ticketLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"ticket declares " + ticketLength + " bytes with " + body.readRemaining() + " remaining");
		}
		byte[] ticket = new byte[ticketLength];
		body.read(ticket);
		List<TlsExtension> extensions = readExtensionsVector(body, "NewSessionTicket");
		return new NewSessionTicketMessage(ticketLifetime, ticketAgeAdd, nonce, ticket, extensions);
	}

	private static TlsHandshakeMessage readCertificate(ByteBuf body) throws TruncatedDataException, MalformedDataException, TlsAlertException {
		requireRemaining(body, 1, "certificate_request_context");
		int contextLength = body.readByte() & 0xFF;
		if (contextLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"certificate_request_context declares " + contextLength + " bytes with " + body.readRemaining() + " remaining");
		}
		byte[] context = new byte[contextLength];
		body.read(context);
		requireRemaining(body, 3, "certificate_list");
		int listLength = readUint24(body);
		if (listLength != body.readRemaining()) {
			throw new MalformedDataException(
				"certificate_list declares " + listLength + " bytes with " + body.readRemaining() + " remaining");
		}
		List<CertificateEntry> entries = new ArrayList<>();
		while (body.canRead()) {
			if (entries.size() == CertificateMessage.MAX_CERTIFICATE_ENTRIES) {
				throw new MalformedDataException(
					"Certificate message holds more than " + CertificateMessage.MAX_CERTIFICATE_ENTRIES + " entries");
			}
			requireRemaining(body, 3, "certificate entry");
			int certificateLength = readUint24(body);
			if (certificateLength == 0) {
				throw new MalformedDataException("certificate_data must be at least 1 byte (RFC 8446 §4.4.2)");
			}
			if (certificateLength > body.readRemaining()) {
				throw new TruncatedDataException(
					"certificate_data declares " + certificateLength + " bytes with " + body.readRemaining() + " remaining");
			}
			byte[] certificateBytes = new byte[certificateLength];
			body.read(certificateBytes);
			requireRemaining(body, 2, "certificate entry extensions");
			int extensionsLength = readShort(body);
			if (extensionsLength > body.readRemaining()) {
				throw new TruncatedDataException(
					"certificate entry extensions declare " + extensionsLength + " bytes with " + body.readRemaining() + " remaining");
			}
			byte[] extensionsBytes = new byte[extensionsLength];
			body.read(extensionsBytes);
			entries.add(new CertificateEntry(certificateBytes,
				TlsExtensions.readList(ByteBuf.wrapForReading(extensionsBytes))));
		}
		return new CertificateMessage(context, entries);
	}

	private static TlsHandshakeMessage readCertificateVerify(ByteBuf body) throws TruncatedDataException, MalformedDataException {
		requireRemaining(body, 2 + 2, "CertificateVerify fixed fields");
		int signatureScheme = readShort(body);
		int signatureLength = readShort(body);
		if (signatureLength == 0) {
			throw new MalformedDataException("signature must be at least 1 byte (RFC 8446 §4.4.3)");
		}
		if (signatureLength > body.readRemaining()) {
			throw new TruncatedDataException(
				"signature declares " + signatureLength + " bytes with " + body.readRemaining() + " remaining");
		}
		byte[] signature = new byte[signatureLength];
		body.read(signature);
		return new CertificateVerifyMessage(signatureScheme, signature);
	}

	private static TlsHandshakeMessage readFinished(ByteBuf body) {
		byte[] verifyData = new byte[body.readRemaining()];
		body.read(verifyData);
		return new FinishedMessage(verifyData);
	}

	/**
	 * Reads the 2-byte-length-prefixed extensions vector that ends a message body
	 * (RFC 8446 §4.2): the declared length must cover exactly the rest of the body.
	 */
	private static List<TlsExtension> readExtensionsVector(ByteBuf body, String owner)
			throws TruncatedDataException, MalformedDataException, TlsAlertException {
		requireRemaining(body, 2, owner + " extensions vector");
		int extensionsLength = readShort(body);
		if (extensionsLength != body.readRemaining()) {
			throw new MalformedDataException(
				owner + " extensions vector declares " + extensionsLength + " bytes with " + body.readRemaining() + " remaining");
		}
		return TlsExtensions.readList(body);
	}

	// ---- scalar helpers (big-endian, matching the TLS wire format) ----

	static void writeUint24(ByteBuf buf, int v) {
		buf.writeByte((byte) (v >>> 16));
		buf.writeByte((byte) (v >>> 8));
		buf.writeByte((byte) v);
	}

	static int readUint24(ByteBuf buf) {
		return ((buf.readByte() & 0xFF) << 16) | ((buf.readByte() & 0xFF) << 8) | (buf.readByte() & 0xFF);
	}

	private static int readShort(ByteBuf buf) {
		return ((buf.readByte() & 0xFF) << 8) | (buf.readByte() & 0xFF);
	}

	private static long readUint32(ByteBuf buf) {
		return ((long) readShort(buf) << 16) | readShort(buf);
	}

	private static void requireRemaining(ByteBuf buf, int n, String what) throws TruncatedDataException {
		if (buf.readRemaining() < n) {
			throw new TruncatedDataException("Truncated " + what + ": expected " + n + " more byte(s), only " + buf.readRemaining() + " remain");
		}
	}
}
