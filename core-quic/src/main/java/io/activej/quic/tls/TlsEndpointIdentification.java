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

import org.jetbrains.annotations.Nullable;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static io.activej.quic.tls.TlsAlerts.BAD_CERTIFICATE;

/**
 * RFC 6125 endpoint identification for the client engine (FR-011) — package-private, not API.
 * For a hostname reference the presented identifiers are the leaf certificate's subjectAltName
 * {@code dNSName} entries (list-entry type 2); the CN of the subject DN is consulted only when
 * the certificate carries no {@code dNSName} SAN at all (the legacy fallback). For an IP-literal
 * reference only {@code iPAddress} SANs (type 7) are consulted — never {@code dNSName} entries
 * and never the CN (RFC 6125 §6.4.1: an IP address is not a DNS name).
 * <p>
 * Matching rules (RFC 6125 §6.4.3): case-insensitive ASCII comparison; a presented identifier
 * may carry a single wildcard {@code *} as its complete left-most label, matching exactly one
 * label of the reference identifier — never zero labels (the bare parent), never more than one,
 * and partial wildcards ({@code foo*.example.test}) never match. As a conservative public-suffix
 * guard (§6.4.3 warns against wildcards spanning a public suffix; no public-suffix list is
 * consulted) the wildcarded domain must have at least two labels — {@code *.com} matches
 * nothing.
 */
final class TlsEndpointIdentification {
	private static final int SAN_DNS_NAME_TYPE = 2;
	private static final int SAN_IP_ADDRESS_TYPE = 7;

	private TlsEndpointIdentification() {
	}

	/**
	 * Verifies that {@code leaf} identifies {@code hostname} — a DNS hostname or an IP literal.
	 *
	 * @throws TlsAlertException {@code bad_certificate} when no presented identifier matches
	 */
	static void verify(X509Certificate leaf, String hostname) throws TlsAlertException {
		try {
			Collection<List<?>> subjectAlternativeNames = leaf.getSubjectAlternativeNames();
			byte[] ipLiteral = parseIpLiteral(hostname);
			if (ipLiteral != null) {
				// IP literal: iPAddress SANs only (RFC 6125 §6.4.1) — no dNSName, no CN fallback
				if (subjectAlternativeNames != null) {
					for (List<?> san : subjectAlternativeNames) {
						if (san.size() >= 2 && san.get(0) instanceof Integer type &&
							type == SAN_IP_ADDRESS_TYPE && san.get(1) instanceof byte[] address &&
							Arrays.equals(address, ipLiteral)) {
							return;
						}
					}
				}
				throw failure(hostname);
			}
			if (subjectAlternativeNames != null) {
				boolean anyDnsName = false;
				for (List<?> san : subjectAlternativeNames) {
					if (san.size() >= 2 && san.get(0) instanceof Integer type &&
						type == SAN_DNS_NAME_TYPE && san.get(1) instanceof String dnsName) {
						anyDnsName = true;
						if (matches(dnsName, hostname)) {
							return;
						}
					}
				}
				if (anyDnsName) {
					throw failure(hostname);
				}
				// no dNSName SANs at all — fall through to the CN fallback
			}
			LdapName subject = new LdapName(leaf.getSubjectX500Principal().getName());
			for (Rdn rdn : subject.getRdns()) {
				if ("CN".equalsIgnoreCase(rdn.getType()) && rdn.getValue() instanceof String commonName) {
					if (matches(commonName, hostname)) {
						return;
					}
				}
			}
			throw failure(hostname);
		} catch (TlsAlertException e) {
			throw e;
		} catch (Exception e) {
			// an unparseable subject DN / SAN structure is a certificate problem, not a mismatch detail
			throw new TlsAlertException(BAD_CERTIFICATE,
				"Cannot extract the presented identifiers of the server certificate: " + e.getMessage());
		}
	}

	/**
	 * RFC 6125 §6.4.3 matching of one presented DNS identifier against the reference
	 * identifier: case-insensitive; a single whole-label left-most wildcard matches exactly one
	 * label, and the wildcarded domain must have at least two labels (the conservative
	 * public-suffix guard — {@code *.com} never matches).
	 */
	static boolean matches(String presented, String hostname) {
		String presentedLower = presented.toLowerCase(Locale.ROOT);
		String hostnameLower = hostname.toLowerCase(Locale.ROOT);
		if (presentedLower.startsWith("*.")) {
			String suffix = presentedLower.substring(1); // ".example.test"
			if (presentedLower.indexOf('*', 1) >= 0) {
				return false; // a wildcard anywhere but the left-most label never matches
			}
			if (suffix.indexOf('.', 1) < 0) {
				return false; // "*.com"-style wildcard over a bare public suffix never matches
			}
			if (!hostnameLower.endsWith(suffix)) {
				return false;
			}
			String leftMost = hostnameLower.substring(0, hostnameLower.length() - suffix.length());
			return !leftMost.isEmpty() && leftMost.indexOf('.') < 0;
		}
		if (presentedLower.indexOf('*') >= 0) {
			return false; // partial wildcards are not matched
		}
		return presentedLower.equals(hostnameLower);
	}

	/**
	 * Parses an IPv4/IPv6 literal into its 4- or 16-byte network-order form, or returns
	 * {@code null} when {@code literal} is not a syntactically valid IP literal (a hostname).
	 * Pure syntax — never a DNS lookup. IPv4 is a strict dotted quad (no octal/hex, no leading
	 * zeros); IPv6 supports {@code ::} compression and an embedded dotted-quad tail.
	 */
	static byte @Nullable [] parseIpLiteral(String literal) {
		return literal.indexOf(':') >= 0 ? parseIpv6(literal) : parseIpv4(literal);
	}

	private static byte @Nullable [] parseIpv4(String literal) {
		String[] parts = literal.split("\\.", -1);
		if (parts.length != 4) return null;
		byte[] bytes = new byte[4];
		for (int i = 0; i < 4; i++) {
			String part = parts[i];
			if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
				return null; // empty, oversized, or a leading-zero part (octal ambiguity)
			}
			int value = 0;
			for (int j = 0; j < part.length(); j++) {
				char c = part.charAt(j);
				if (c < '0' || c > '9') return null;
				value = value * 10 + (c - '0');
			}
			if (value > 255) return null;
			bytes[i] = (byte) value;
		}
		return bytes;
	}

	private static byte @Nullable [] parseIpv6(String literal) {
		if (literal.isEmpty()) return null;
		int doubleColon = literal.indexOf("::");
		boolean compressed = doubleColon >= 0;
		if (compressed && literal.indexOf("::", doubleColon + 2) >= 0) {
			return null; // at most one "::" compression run
		}
		String head = compressed ? literal.substring(0, doubleColon) : literal;
		String tail = compressed ? literal.substring(doubleColon + 2) : "";
		int[] headGroups = parseIpv6Side(head, false);
		int[] tailGroups = parseIpv6Side(tail, true);
		if (headGroups == null || tailGroups == null) return null;
		int total = headGroups.length + tailGroups.length;
		if (compressed ? total > 7 : total != 8) {
			return null; // "::" compresses at least one group; an uncompressed form needs exactly 8
		}
		byte[] bytes = new byte[16];
		int pos = 0;
		for (int group : headGroups) {
			bytes[pos++] = (byte) (group >>> 8);
			bytes[pos++] = (byte) group;
		}
		pos += 16 - total * 2; // the compressed zero run
		for (int group : tailGroups) {
			bytes[pos++] = (byte) (group >>> 8);
			bytes[pos++] = (byte) group;
		}
		return bytes;
	}

	/**
	 * Parses one colon-separated run of 16-bit groups; the last component may be an embedded
	 * dotted-quad IPv4 tail (counting as two groups) when {@code allowEmbeddedIpv4} — the tail
	 * run only (RFC 4291 §2.2 "mixed" form).
	 */
	private static int @Nullable [] parseIpv6Side(String side, boolean allowEmbeddedIpv4) {
		if (side.isEmpty()) return new int[0];
		String[] parts = side.split(":", -1);
		List<Integer> groups = new ArrayList<>(parts.length + 1);
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.isEmpty()) return null;
			if (allowEmbeddedIpv4 && i == parts.length - 1 && part.indexOf('.') >= 0) {
				byte[] ipv4 = parseIpv4(part);
				if (ipv4 == null) return null;
				groups.add(((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF));
				groups.add(((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF));
				continue;
			}
			if (part.length() > 4) return null;
			int value;
			try {
				value = Integer.parseInt(part, 16);
			} catch (NumberFormatException e) {
				return null;
			}
			groups.add(value);
		}
		int[] result = new int[groups.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = groups.get(i);
		}
		return result;
	}

	private static TlsAlertException failure(String hostname) {
		return new TlsAlertException(BAD_CERTIFICATE,
			"Endpoint identification failed: the server certificate does not identify '" + hostname + "' (RFC 6125)");
	}
}
