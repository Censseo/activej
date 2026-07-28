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

/**
 * TLS 1.3 alert description codes (RFC 8446 §6) used by the QUIC profile.
 * <p>
 * The connection layer maps a code to a CONNECTION_CLOSE frame of type {@code 0x0100 + code}
 * (RFC 9001 §4.8); this class only names the codes.
 */
public final class TlsAlerts {
	public static final int UNEXPECTED_MESSAGE = 10;
	public static final int HANDSHAKE_FAILURE = 40;
	public static final int BAD_CERTIFICATE = 42;
	public static final int CERTIFICATE_EXPIRED = 45;
	public static final int ILLEGAL_PARAMETER = 47;
	public static final int UNKNOWN_CA = 48;
	public static final int DECODE_ERROR = 50;
	public static final int DECRYPT_ERROR = 51;
	public static final int PROTOCOL_VERSION = 70;
	public static final int INTERNAL_ERROR = 80;
	public static final int MISSING_EXTENSION = 109;
	public static final int UNSUPPORTED_EXTENSION = 110;
	public static final int UNRECOGNIZED_NAME = 112;
	public static final int NO_APPLICATION_PROTOCOL = 120;

	private TlsAlerts() {
	}

	/** RFC 8446 §6 alert description name, or {@code "unknown(<code>)"} for a code outside this set. */
	public static String name(int alertCode) {
		return switch (alertCode) {
			case UNEXPECTED_MESSAGE -> "unexpected_message";
			case HANDSHAKE_FAILURE -> "handshake_failure";
			case BAD_CERTIFICATE -> "bad_certificate";
			case CERTIFICATE_EXPIRED -> "certificate_expired";
			case ILLEGAL_PARAMETER -> "illegal_parameter";
			case UNKNOWN_CA -> "unknown_ca";
			case DECODE_ERROR -> "decode_error";
			case DECRYPT_ERROR -> "decrypt_error";
			case PROTOCOL_VERSION -> "protocol_version";
			case INTERNAL_ERROR -> "internal_error";
			case MISSING_EXTENSION -> "missing_extension";
			case UNSUPPORTED_EXTENSION -> "unsupported_extension";
			case UNRECOGNIZED_NAME -> "unrecognized_name";
			case NO_APPLICATION_PROTOCOL -> "no_application_protocol";
			default -> "unknown(" + alertCode + ")";
		};
	}
}
