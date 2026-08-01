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

package io.activej.http3.qpack;

import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;

/**
 * The RFC 9204 Appendix A static table: 99 (name, value) entries at indices 0-98, plus the two
 * lookup indexes an encoder needs.
 * <p>
 * Built <b>once at class-init</b>, a JVM-wide immutable singleton — never per connection or per
 * request (spec Performance table). Names are pre-interned through {@link HttpHeaders#of(String)}
 * so a decoded field name resolves to the exact {@link HttpHeader} object identity
 * {@code core-http}'s multimap already expects.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#appendix-A">RFC 9204 Appendix A — Static
 * Table</a>
 */
public final class QpackStaticTable {
	/** The number of static table entries. Valid indices are {@code [0, SIZE)}. */
	public static final int SIZE = 99;

	private static final HttpHeader[] NAMES = new HttpHeader[SIZE];
	private static final byte[][] VALUES = new byte[SIZE][];

	/** name -> lowest static index carrying that name (for Literal Field Line with Name Reference). */
	private static final Map<HttpHeader, Integer> NAME_INDEX = new HashMap<>();

	/** (name, value) -> static index (for Indexed Field Line). */
	private static final Map<NameValue, Integer> NAME_VALUE_INDEX = new HashMap<>();

	static {
		define(0, ":authority", "");
		define(1, ":path", "/");
		define(2, "age", "0");
		define(3, "content-disposition", "");
		define(4, "content-length", "0");
		define(5, "cookie", "");
		define(6, "date", "");
		define(7, "etag", "");
		define(8, "if-modified-since", "");
		define(9, "if-none-match", "");
		define(10, "last-modified", "");
		define(11, "link", "");
		define(12, "location", "");
		define(13, "referer", "");
		define(14, "set-cookie", "");
		define(15, ":method", "CONNECT");
		define(16, ":method", "DELETE");
		define(17, ":method", "GET");
		define(18, ":method", "HEAD");
		define(19, ":method", "OPTIONS");
		define(20, ":method", "POST");
		define(21, ":method", "PUT");
		define(22, ":scheme", "http");
		define(23, ":scheme", "https");
		define(24, ":status", "103");
		define(25, ":status", "200");
		define(26, ":status", "304");
		define(27, ":status", "404");
		define(28, ":status", "503");
		define(29, "accept", "*/*");
		define(30, "accept", "application/dns-message");
		define(31, "accept-encoding", "gzip, deflate, br");
		define(32, "accept-ranges", "bytes");
		define(33, "access-control-allow-headers", "cache-control");
		define(34, "access-control-allow-headers", "content-type");
		define(35, "access-control-allow-origin", "*");
		define(36, "cache-control", "max-age=0");
		define(37, "cache-control", "max-age=2592000");
		define(38, "cache-control", "max-age=604800");
		define(39, "cache-control", "no-cache");
		define(40, "cache-control", "no-store");
		define(41, "cache-control", "public, max-age=31536000");
		define(42, "content-encoding", "br");
		define(43, "content-encoding", "gzip");
		define(44, "content-type", "application/dns-message");
		define(45, "content-type", "application/javascript");
		define(46, "content-type", "application/json");
		define(47, "content-type", "application/x-www-form-urlencoded");
		define(48, "content-type", "image/gif");
		define(49, "content-type", "image/jpeg");
		define(50, "content-type", "image/png");
		define(51, "content-type", "text/css");
		define(52, "content-type", "text/html; charset=utf-8");
		define(53, "content-type", "text/plain");
		define(54, "content-type", "text/plain;charset=utf-8");
		define(55, "range", "bytes=0-");
		define(56, "strict-transport-security", "max-age=31536000");
		define(57, "strict-transport-security", "max-age=31536000; includesubdomains");
		define(58, "strict-transport-security", "max-age=31536000; includesubdomains; preload");
		define(59, "vary", "accept-encoding");
		define(60, "vary", "origin");
		define(61, "x-content-type-options", "nosniff");
		define(62, "x-xss-protection", "1; mode=block");
		define(63, ":status", "100");
		define(64, ":status", "204");
		define(65, ":status", "206");
		define(66, ":status", "302");
		define(67, ":status", "400");
		define(68, ":status", "403");
		define(69, ":status", "421");
		define(70, ":status", "425");
		define(71, ":status", "500");
		define(72, "accept-language", "");
		define(73, "access-control-allow-credentials", "FALSE");
		define(74, "access-control-allow-credentials", "TRUE");
		define(75, "access-control-allow-headers", "*");
		define(76, "access-control-allow-methods", "get");
		define(77, "access-control-allow-methods", "get, post, options");
		define(78, "access-control-allow-methods", "options");
		define(79, "access-control-expose-headers", "content-length");
		define(80, "access-control-request-headers", "content-type");
		define(81, "access-control-request-method", "get");
		define(82, "access-control-request-method", "post");
		define(83, "alt-svc", "clear");
		define(84, "authorization", "");
		define(85, "content-security-policy", "script-src 'none'; object-src 'none'; base-uri 'none'");
		define(86, "early-data", "1");
		define(87, "expect-ct", "");
		define(88, "forwarded", "");
		define(89, "if-range", "");
		define(90, "origin", "");
		define(91, "purpose", "prefetch");
		define(92, "server", "");
		define(93, "timing-allow-origin", "*");
		define(94, "upgrade-insecure-requests", "1");
		define(95, "user-agent", "");
		define(96, "x-forwarded-for", "");
		define(97, "x-frame-options", "deny");
		define(98, "x-frame-options", "sameorigin");

		for (int i = 0; i < SIZE; i++) {
			if (NAMES[i] == null || VALUES[i] == null) {
				throw new AssertionError("QPACK static table entry " + i + " was never defined");
			}
		}
	}

	private QpackStaticTable() {}

	private static void define(int index, String name, String value) {
		HttpHeader header = HttpHeaders.of(name);
		byte[] valueBytes = encodeAscii(value);
		NAMES[index] = header;
		VALUES[index] = valueBytes;
		NAME_INDEX.putIfAbsent(header, index);
		NAME_VALUE_INDEX.put(new NameValue(header, valueBytes), index);
	}

	/** The interned field name at {@code index}. */
	public static HttpHeader name(int index) {
		return NAMES[index];
	}

	/** The field value at {@code index}. Callers must not mutate the returned array. */
	public static byte[] value(int index) {
		return VALUES[index];
	}

	/** The lowest static index carrying {@code name}, or {@code -1} if none does. */
	public static int indexOfName(HttpHeader name) {
		Integer index = NAME_INDEX.get(name);
		return index != null ? index : -1;
	}

	/** The static index of the exact ({@code name}, {@code value}) pair, or {@code -1} if none matches. */
	public static int indexOfNameAndValue(HttpHeader name, byte[] value) {
		Integer index = NAME_VALUE_INDEX.get(new NameValue(name, value));
		return index != null ? index : -1;
	}

	private static final class NameValue {
		private final HttpHeader name;
		private final byte[] value;

		NameValue(HttpHeader name, byte[] value) {
			this.name = name;
			this.value = value;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof NameValue that)) return false;
			return name.equals(that.name) && Arrays.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return 31 * name.hashCode() + Arrays.hashCode(value);
		}
	}
}
