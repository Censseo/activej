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
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.exception.MalformedDataException;
import io.activej.quic.codec.QuicVarInts;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A TLS 1.3 session ticket (RFC 8446 §4.6.1) as this stack uses it for QUIC resumption
 * (spec FR-042), with two faces:
 * <ul>
 *     <li>the <b>sealed blob</b> — {@link #identity()}, the opaque {@code NewSessionTicket.ticket}
 *     that travels on the wire and that only the issuing server can open;</li>
 *     <li>the <b>plaintext contents</b> — everything needed to resume: the resumption secret, the
 *     cipher suite, the ALPN, the server name, the issue time, the ticket-age obfuscation offset,
 *     the transport parameters and the opaque application settings.</li>
 * </ul>
 * A ticket is deliberately <b>not</b> a dictionary key into server-side session state (spec FR-042):
 * everything the server needs travels inside the blob, so a ticket that cannot be opened simply
 * causes a full handshake and costs the server nothing it has to remember.
 * <p>
 * <b>The plaintext layout is a purely internal format with no cross-version contract.</b> A build
 * that changes it cannot open its own older tickets, which degrades to a full handshake — the safe
 * direction. Nothing outside this package may depend on the byte layout; {@link QuicTicketKeys} is
 * its only reader and writer.
 * <p>
 * <b>Secret material.</b> {@link #resumptionSecret()} and {@link #identity()} never appear in a log
 * line, an exception message, {@code toString} or a JMX attribute (spec FR-050, SI-6);
 * {@link #toString()} prints the origin and the suite only, and deliberately not
 * {@link #ticketAgeAdd()}, which is the value that makes an obfuscated age unlinkable.
 * <p>
 * Every {@code byte[]} is cloned on the way in and on the way out.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.6.1">RFC 8446 §4.6.1</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-7.4.1">RFC 9000 §7.4.1</a>
 */
public final class QuicSessionTicket {
	private final String serverName;
	private final String alpn;
	private final TlsCipherSuite cipherSuite;
	private final byte[] resumptionSecret;
	private final byte[] serverNameBytes;
	private final byte[] alpnBytes;

	private byte[] identity = new byte[0];
	private byte[] applicationSettings = new byte[0];
	private long issuedAtMillis;
	private long lifetimeMillis;
	private long ticketAgeAdd;
	private QuicTransportParameters transportParameters;

	private QuicSessionTicket(String serverName, String alpn, TlsCipherSuite cipherSuite, byte[] resumptionSecret) {
		this.serverName = serverName;
		this.alpn = alpn;
		this.cipherSuite = cipherSuite;
		this.resumptionSecret = resumptionSecret;
		this.serverNameBytes = serverName.getBytes(StandardCharsets.UTF_8);
		this.alpnBytes = alpn.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Starts the one-shot builder. All four arguments are mandatory and identify the session being
	 * resumed; {@code withIssuedAt}, {@code withLifetime}, {@code withTicketAgeAdd} and
	 * {@code withTransportParameters} are mandatory too and are refused at {@code build()} when unset.
	 *
	 * @param serverName the origin this ticket may resume — a ticket for one origin never resumes
	 *        another (spec FR-047)
	 * @param alpn the application protocol this ticket may resume (spec FR-047)
	 * @param cipherSuite the suite negotiated on the original connection; resumption must propose it
	 * @param resumptionSecret the RFC 8446 §4.6.1 resumption secret — <b>secret material</b>, cloned
	 */
	public static Builder builder(String serverName, String alpn, TlsCipherSuite cipherSuite, byte[] resumptionSecret) {
		return new QuicSessionTicket(
			Objects.requireNonNull(serverName, "serverName"),
			Objects.requireNonNull(alpn, "alpn"),
			Objects.requireNonNull(cipherSuite, "cipherSuite"),
			Objects.requireNonNull(resumptionSecret, "resumptionSecret").clone())
			.new Builder();
	}

	/**
	 * The opaque sealed blob offered as the PSK identity — {@code NewSessionTicket.ticket}
	 * (RFC 8446 §4.6.1). Empty until the ticket has been sealed by {@link QuicTicketKeys#seal};
	 * {@link QuicTicketKeys#open} returns a ticket carrying the blob it was opened from.
	 */
	public byte[] identity() {
		return identity.clone();
	}

	/** The resumption secret — <b>secret material</b> (spec FR-050). */
	public byte[] resumptionSecret() {
		return resumptionSecret.clone();
	}

	/** The suite negotiated on the original connection; a ticket resumes under this suite only. */
	public TlsCipherSuite cipherSuite() {
		return cipherSuite;
	}

	/** The application protocol this ticket may resume. */
	public String alpn() {
		return alpn;
	}

	/** The origin this ticket may resume. */
	public String serverName() {
		return serverName;
	}

	/** Wall-clock time the ticket was issued, in milliseconds. */
	public long issuedAtMillis() {
		return issuedAtMillis;
	}

	/** How long the ticket stays usable from {@link #issuedAtMillis()}, in milliseconds. */
	public long lifetimeMillis() {
		return lifetimeMillis;
	}

	/** The uint32 obfuscation offset added to the reported ticket age (RFC 8446 §4.2.11.1). */
	public long ticketAgeAdd() {
		return ticketAgeAdd;
	}

	/**
	 * The transport parameters the client must remember and obey in early data (RFC 9000 §7.4.1),
	 * already filtered by {@link #rememberableParameters}: the eight excluded parameters can never
	 * be present.
	 */
	public QuicTransportParameters transportParameters() {
		return transportParameters;
	}

	/**
	 * The application-layer settings that go with this ticket, opaque to {@code core-quic} —
	 * {@code core-http3} stores its RFC 9114 SETTINGS here (spec FR-062). Empty means "none
	 * remembered", which the HTTP/3 layer reads as "no early data".
	 */
	public byte[] applicationSettings() {
		return applicationSettings.clone();
	}

	/**
	 * Age of the ticket at {@code nowMillis}, floored at 0 so a clock that has moved backwards
	 * reports a fresh ticket rather than a negative age.
	 */
	public long ageMillisAt(long nowMillis) {
		return Math.max(0, nowMillis - issuedAtMillis);
	}

	/** Whether the ticket's lifetime has elapsed at {@code nowMillis}. An expired ticket is a full handshake, never a failure (spec FR-045). */
	public boolean isExpiredAt(long nowMillis) {
		return ageMillisAt(nowMillis) >= lifetimeMillis;
	}

	/** Whether this ticket may be offered for exactly this origin and application protocol (spec FR-047). */
	public boolean isFor(String serverName, String alpn) {
		return this.serverName.equals(serverName) && this.alpn.equals(alpn);
	}

	/**
	 * FR-043a (a): whether one sealed ticket of {@code sealedTicketLength} bytes is within the
	 * configured {@code maxSessionTicketSize}. A {@code NewSessionTicket} arrives after the
	 * handshake, from a peer that is already authenticated but not therefore trusted to be bounded.
	 * <p>
	 * The predicate is pure; mapping {@code false} onto a connection error is the engine's job.
	 */
	public static boolean isSealedSizeWithinLimit(int sealedTicketLength, long maxSessionTicketSizeBytes) {
		return sealedTicketLength >= 0 && sealedTicketLength <= maxSessionTicketSizeBytes;
	}

	/**
	 * FR-043a (b): whether one more ticket may be accepted on a connection that has already accepted
	 * {@code ticketsAcceptedSoFar} of them, given {@code maxSessionTicketsPerConnection}. Without
	 * this bound a server sending unbounded post-handshake tickets buys an unbounded number of PSK
	 * derivations on the client's reactor thread.
	 * <p>
	 * The predicate is pure; mapping {@code false} onto a connection error is the engine's job.
	 */
	public static boolean isCountWithinLimit(int ticketsAcceptedSoFar, int maxSessionTicketsPerConnection) {
		return ticketsAcceptedSoFar >= 0 && ticketsAcceptedSoFar < maxSessionTicketsPerConnection;
	}

	/**
	 * Drops the eight parameters RFC 9000 §7.4.1 forbids remembering across a resumption —
	 * {@code original_destination_connection_id}, {@code stateless_reset_token},
	 * {@code preferred_address}, {@code initial_source_connection_id},
	 * {@code retry_source_connection_id} (all cleared) and {@code max_idle_timeout},
	 * {@code ack_delay_exponent}, {@code max_ack_delay} (returned to their RFC 9000 §18.2 defaults).
	 * <p>
	 * Everything else is preserved, {@code disable_active_migration},
	 * {@code active_connection_id_limit} and {@code max_udp_payload_size} included.
	 */
	public static QuicTransportParameters rememberableParameters(QuicTransportParameters parameters) {
		return new QuicTransportParameters(
			null,
			0,
			null,
			parameters.maxUdpPayloadSize(),
			parameters.initialMaxData(),
			parameters.initialMaxStreamDataBidiLocal(),
			parameters.initialMaxStreamDataBidiRemote(),
			parameters.initialMaxStreamDataUni(),
			parameters.initialMaxStreamsBidi(),
			parameters.initialMaxStreamsUni(),
			QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT,
			QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,
			parameters.disableActiveMigration(),
			null,
			parameters.activeConnectionIdLimit(),
			null,
			null);
	}

	@Override
	public String toString() {
		return "QuicSessionTicket[" + serverName + ", " + alpn + ", " + cipherSuite + ']';
	}

	/** Exact length {@link #writePlaintextTo} will emit. */
	int plaintextLength() {
		int transportParametersLength = transportParameters.encodedLength();
		return QuicVarInts.encodedLength(cipherSuite.code())
			+ QuicVarInts.encodedLength(issuedAtMillis)
			+ QuicVarInts.encodedLength(lifetimeMillis)
			+ QuicVarInts.encodedLength(ticketAgeAdd)
			+ blockLength(resumptionSecret.length)
			+ blockLength(serverNameBytes.length)
			+ blockLength(alpnBytes.length)
			+ blockLength(transportParametersLength)
			+ blockLength(applicationSettings.length);
	}

	/**
	 * Writes the plaintext contents. {@link #identity()} is deliberately absent: it <i>is</i> the
	 * sealing of these bytes, so it cannot be one of them.
	 */
	void writePlaintextTo(ByteBuf out) {
		QuicVarInts.write(out, cipherSuite.code());
		QuicVarInts.write(out, issuedAtMillis);
		QuicVarInts.write(out, lifetimeMillis);
		QuicVarInts.write(out, ticketAgeAdd);
		writeBlock(out, resumptionSecret);
		writeBlock(out, serverNameBytes);
		writeBlock(out, alpnBytes);
		QuicVarInts.write(out, transportParameters.encodedLength());
		transportParameters.writeTo(out);
		writeBlock(out, applicationSettings);
	}

	/**
	 * Reads plaintext contents written by {@link #writePlaintextTo}, returning a ticket whose
	 * {@link #identity()} is empty — the caller attaches the blob it opened.
	 * <p>
	 * Every declared length is checked against the remaining bytes before anything is allocated
	 * (SI-4). These bytes are AEAD-authenticated by the time they get here, so this is defence in
	 * depth rather than the primary control; it costs nothing and removes the class of bug where a
	 * future caller feeds this method something unauthenticated.
	 *
	 * @throws MalformedDataException on a truncated, over-declared or trailing-byte encoding, or on
	 * an unknown cipher-suite code
	 */
	static QuicSessionTicket readPlaintext(ByteBuf in) throws MalformedDataException {
		long suiteCode = QuicVarInts.read(in);
		TlsCipherSuite cipherSuite = TlsCipherSuite.of((int) suiteCode);
		if (cipherSuite == null || suiteCode != cipherSuite.code()) {
			throw new MalformedDataException("Session ticket names an unknown cipher suite 0x" + Long.toHexString(suiteCode));
		}
		long issuedAtMillis = QuicVarInts.read(in);
		long lifetimeMillis = QuicVarInts.read(in);
		long ticketAgeAdd = QuicVarInts.read(in);
		byte[] resumptionSecret = readBlock(in, "resumption secret");
		String serverName = new String(readBlock(in, "server name"), StandardCharsets.UTF_8);
		String alpn = new String(readBlock(in, "ALPN"), StandardCharsets.UTF_8);
		QuicTransportParameters transportParameters =
			QuicTransportParameters.read(ByteBuf.wrapForReading(readBlock(in, "transport parameters")));
		byte[] applicationSettings = readBlock(in, "application settings");
		if (in.canRead()) {
			throw new MalformedDataException("Session ticket has " + in.readRemaining() + " trailing bytes");
		}
		if (lifetimeMillis <= 0 || resumptionSecret.length == 0 || serverName.isEmpty() || alpn.isEmpty() ||
			(ticketAgeAdd & ~0xFFFFFFFFL) != 0) {
			throw new MalformedDataException("Session ticket contents are out of range");
		}
		return builder(serverName, alpn, cipherSuite, resumptionSecret)
			.withIssuedAt(issuedAtMillis)
			.withLifetime(lifetimeMillis)
			.withTicketAgeAdd(ticketAgeAdd)
			.withTransportParameters(transportParameters)
			.withApplicationSettings(applicationSettings)
			.build();
	}

	private static int blockLength(int length) {
		return QuicVarInts.encodedLength(length) + length;
	}

	private static void writeBlock(ByteBuf out, byte[] bytes) {
		QuicVarInts.write(out, bytes.length);
		out.put(bytes);
	}

	private static byte[] readBlock(ByteBuf in, String what) throws MalformedDataException {
		long length = QuicVarInts.read(in);
		if (length > in.readRemaining()) {
			throw new MalformedDataException(
				"Session ticket declares " + length + " bytes of " + what + " with " + in.readRemaining() + " remaining");
		}
		byte[] bytes = new byte[(int) length];
		in.read(bytes);
		return bytes;
	}

	/**
	 * The one-shot {@code AbstractBuilder} for {@link QuicSessionTicket}. {@code withIssuedAt},
	 * {@code withLifetime}, {@code withTicketAgeAdd} and {@code withTransportParameters} are
	 * mandatory; tracking them by flag rather than by sentinel is what keeps a legal
	 * {@code ticket_age_add} of 0 distinguishable from "never set".
	 */
	public final class Builder extends AbstractBuilder<Builder, QuicSessionTicket> {
		private boolean issuedAtSet;
		private boolean lifetimeSet;
		private boolean ticketAgeAddSet;
		private boolean transportParametersSet;

		private Builder() {
		}

		/** The sealed blob this ticket is offered as; set by {@link QuicTicketKeys} on both sides. */
		public Builder withIdentity(byte[] identity) {
			checkNotBuilt(this);
			QuicSessionTicket.this.identity = Objects.requireNonNull(identity, "identity").clone();
			return this;
		}

		/** Wall-clock issue time (RFC 8446 §4.6.1), the base of both the lifetime and the obfuscated age. */
		public Builder withIssuedAt(long issuedAtMillis) {
			checkNotBuilt(this);
			QuicSessionTicket.this.issuedAtMillis = issuedAtMillis;
			this.issuedAtSet = true;
			return this;
		}

		/** {@code ticket_lifetime} in milliseconds (RFC 8446 §4.6.1); must be positive. */
		public Builder withLifetime(long lifetimeMillis) {
			checkNotBuilt(this);
			QuicSessionTicket.this.lifetimeMillis = lifetimeMillis;
			this.lifetimeSet = true;
			return this;
		}

		/** {@code ticket_age_add}, a uint32 (RFC 8446 §4.2.11.1). Zero is legal. */
		public Builder withTicketAgeAdd(long ticketAgeAdd) {
			checkNotBuilt(this);
			QuicSessionTicket.this.ticketAgeAdd = ticketAgeAdd;
			this.ticketAgeAddSet = true;
			return this;
		}

		/**
		 * The transport parameters to remember with this ticket. Stored through
		 * {@link #rememberableParameters}, so a built ticket can never carry one of the eight
		 * parameters RFC 9000 §7.4.1 excludes, whatever the caller passes.
		 */
		public Builder withTransportParameters(QuicTransportParameters transportParameters) {
			checkNotBuilt(this);
			QuicSessionTicket.this.transportParameters =
				rememberableParameters(Objects.requireNonNull(transportParameters, "transportParameters"));
			this.transportParametersSet = true;
			return this;
		}

		/** Application-layer settings to remember, opaque here (spec FR-062). Defaults to empty. */
		public Builder withApplicationSettings(byte[] applicationSettings) {
			checkNotBuilt(this);
			QuicSessionTicket.this.applicationSettings =
				Objects.requireNonNull(applicationSettings, "applicationSettings").clone();
			return this;
		}

		@Override
		protected QuicSessionTicket doBuild() {
			if (!issuedAtSet) throw new IllegalStateException("issuedAt is mandatory");
			if (!lifetimeSet) throw new IllegalStateException("lifetime is mandatory");
			if (!ticketAgeAddSet) throw new IllegalStateException("ticketAgeAdd is mandatory (RFC 8446 §4.2.11.1)");
			if (!transportParametersSet) throw new IllegalStateException("transportParameters are mandatory (RFC 9000 §7.4.1)");
			if (issuedAtMillis < 0 || issuedAtMillis > QuicVarInts.MAX_VALUE) {
				throw new IllegalStateException("issuedAt (" + issuedAtMillis + " ms) is out of range");
			}
			if (lifetimeMillis <= 0 || lifetimeMillis > QuicVarInts.MAX_VALUE) {
				throw new IllegalStateException("lifetime (" + lifetimeMillis + " ms) must be positive");
			}
			if ((ticketAgeAdd & ~0xFFFFFFFFL) != 0) {
				throw new IllegalStateException("ticketAgeAdd (" + ticketAgeAdd + ") is a uint32 (RFC 8446 §4.2.11.1)");
			}
			if (resumptionSecret.length == 0) throw new IllegalStateException("resumptionSecret must not be empty");
			if (serverName.isEmpty()) throw new IllegalStateException("serverName must not be empty");
			if (alpn.isEmpty()) throw new IllegalStateException("alpn must not be empty");
			return QuicSessionTicket.this;
		}
	}
}
