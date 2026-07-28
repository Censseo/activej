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
import java.util.List;
import java.util.Objects;

/**
 * The {@code NewSessionTicket} message (RFC 8446 §4.6.1), structure only (FR-015): lifetime,
 * age-add, nonce, ticket and extensions are parsed and exposed, and the client engine discards
 * the ticket without creating any resumption state. The server never issues tickets in this
 * feature.
 */
public final class NewSessionTicketMessage extends TlsHandshakeMessage {
	public static final int TYPE = 4;

	public final long ticketLifetime;
	public final long ticketAgeAdd;
	public final byte[] ticketNonce;
	public final byte[] ticket;
	public final List<TlsExtension> extensions;

	public NewSessionTicketMessage(long ticketLifetime, long ticketAgeAdd,
			byte[] ticketNonce, byte[] ticket, List<TlsExtension> extensions) {
		if ((ticketLifetime & ~0xFFFFFFFFL) != 0 || (ticketAgeAdd & ~0xFFFFFFFFL) != 0) {
			throw new IllegalArgumentException("ticket_lifetime and ticket_age_add are uint32 values");
		}
		if (ticketNonce.length == 0 || ticketNonce.length > 255) {
			throw new IllegalArgumentException("ticket_nonce must be 1..255 bytes: " + ticketNonce.length);
		}
		if (ticket.length == 0 || ticket.length > 0xFFFF) {
			throw new IllegalArgumentException("ticket must be 1..65535 bytes: " + ticket.length);
		}
		this.ticketLifetime = ticketLifetime;
		this.ticketAgeAdd = ticketAgeAdd;
		this.ticketNonce = ticketNonce.clone();
		this.ticket = ticket.clone();
		this.extensions = List.copyOf(extensions);
	}

	/** Defensive copy of {@link #ticketNonce}. */
	public byte[] ticketNonce() {
		return ticketNonce.clone();
	}

	/** Defensive copy of {@link #ticket}. */
	public byte[] ticket() {
		return ticket.clone();
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + 4 + 4 + 1 + ticketNonce.length + 2 + ticket.length + 2 + TlsExtensions.encodedListLength(extensions);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) TYPE);
		TlsMessages.writeUint24(buf, encodedLength() - 4);
		writeUint32(buf, ticketLifetime);
		writeUint32(buf, ticketAgeAdd);
		buf.writeByte((byte) ticketNonce.length);
		buf.put(ticketNonce);
		TlsExtensions.writeShort(buf, ticket.length);
		buf.put(ticket);
		TlsExtensions.writeList(buf, extensions);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof NewSessionTicketMessage other)) return false;
		return ticketLifetime == other.ticketLifetime &&
			ticketAgeAdd == other.ticketAgeAdd &&
			Arrays.equals(ticketNonce, other.ticketNonce) &&
			Arrays.equals(ticket, other.ticket) &&
			extensions.equals(other.extensions);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(ticketLifetime, ticketAgeAdd, extensions);
		result = 31 * result + Arrays.hashCode(ticketNonce);
		result = 31 * result + Arrays.hashCode(ticket);
		return result;
	}

	private static void writeUint32(ByteBuf buf, long v) {
		TlsExtensions.writeShort(buf, (int) (v >>> 16));
		TlsExtensions.writeShort(buf, (int) v);
	}
}
