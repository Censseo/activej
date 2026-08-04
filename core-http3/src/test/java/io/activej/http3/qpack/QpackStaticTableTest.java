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

import io.activej.http.HttpHeaders;
import org.junit.Test;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Every entry byte-exact against RFC 9204 Appendix A (sourced directly from the RFC's HTML table,
 * cross-checked against its fixed-width text rendering to resolve two entries — indices 52 and 57 —
 * where a value wraps across a line and only the HTML form disambiguates a literal space from
 * column padding).
 */
public class QpackStaticTableTest {
	private static final String[][] ENTRIES = {
		{":authority", ""}, {":path", "/"}, {"age", "0"}, {"content-disposition", ""},
		{"content-length", "0"}, {"cookie", ""}, {"date", ""}, {"etag", ""},
		{"if-modified-since", ""}, {"if-none-match", ""}, {"last-modified", ""}, {"link", ""},
		{"location", ""}, {"referer", ""}, {"set-cookie", ""}, {":method", "CONNECT"},
		{":method", "DELETE"}, {":method", "GET"}, {":method", "HEAD"}, {":method", "OPTIONS"},
		{":method", "POST"}, {":method", "PUT"}, {":scheme", "http"}, {":scheme", "https"},
		{":status", "103"}, {":status", "200"}, {":status", "304"}, {":status", "404"},
		{":status", "503"}, {"accept", "*/*"}, {"accept", "application/dns-message"},
		{"accept-encoding", "gzip, deflate, br"}, {"accept-ranges", "bytes"},
		{"access-control-allow-headers", "cache-control"},
		{"access-control-allow-headers", "content-type"}, {"access-control-allow-origin", "*"},
		{"cache-control", "max-age=0"}, {"cache-control", "max-age=2592000"},
		{"cache-control", "max-age=604800"}, {"cache-control", "no-cache"},
		{"cache-control", "no-store"}, {"cache-control", "public, max-age=31536000"},
		{"content-encoding", "br"}, {"content-encoding", "gzip"},
		{"content-type", "application/dns-message"}, {"content-type", "application/javascript"},
		{"content-type", "application/json"}, {"content-type", "application/x-www-form-urlencoded"},
		{"content-type", "image/gif"}, {"content-type", "image/jpeg"}, {"content-type", "image/png"},
		{"content-type", "text/css"}, {"content-type", "text/html; charset=utf-8"},
		{"content-type", "text/plain"}, {"content-type", "text/plain;charset=utf-8"},
		{"range", "bytes=0-"}, {"strict-transport-security", "max-age=31536000"},
		{"strict-transport-security", "max-age=31536000; includesubdomains"},
		{"strict-transport-security", "max-age=31536000; includesubdomains; preload"},
		{"vary", "accept-encoding"}, {"vary", "origin"}, {"x-content-type-options", "nosniff"},
		{"x-xss-protection", "1; mode=block"}, {":status", "100"}, {":status", "204"},
		{":status", "206"}, {":status", "302"}, {":status", "400"}, {":status", "403"},
		{":status", "421"}, {":status", "425"}, {":status", "500"}, {"accept-language", ""},
		{"access-control-allow-credentials", "FALSE"}, {"access-control-allow-credentials", "TRUE"},
		{"access-control-allow-headers", "*"}, {"access-control-allow-methods", "get"},
		{"access-control-allow-methods", "get, post, options"},
		{"access-control-allow-methods", "options"}, {"access-control-expose-headers", "content-length"},
		{"access-control-request-headers", "content-type"}, {"access-control-request-method", "get"},
		{"access-control-request-method", "post"}, {"alt-svc", "clear"}, {"authorization", ""},
		{"content-security-policy", "script-src 'none'; object-src 'none'; base-uri 'none'"},
		{"early-data", "1"}, {"expect-ct", ""}, {"forwarded", ""}, {"if-range", ""}, {"origin", ""},
		{"purpose", "prefetch"}, {"server", ""}, {"timing-allow-origin", "*"},
		{"upgrade-insecure-requests", "1"}, {"user-agent", ""}, {"x-forwarded-for", ""},
		{"x-frame-options", "deny"}, {"x-frame-options", "sameorigin"},
	};

	@Test
	public void hasExactly99Entries() {
		assertEquals(99, QpackStaticTable.SIZE);
		assertEquals(99, ENTRIES.length);
	}

	@Test
	public void everyEntryIsByteExact() {
		// HttpHeader.equals()/hashCode() are content-based (case-insensitive), which is what matters
		// here — pseudo-headers like ":authority" are not among core-http's pre-registered constants,
		// so HttpHeaders.of(...) returns a fresh, content-equal instance on every call rather than the
		// same interned object; asserting "==" against it would be asserting an accident of caching.
		for (int i = 0; i < ENTRIES.length; i++) {
			assertEquals("name at index " + i, HttpHeaders.of(ENTRIES[i][0]), QpackStaticTable.name(i));
			assertArrayEquals("value at index " + i, encodeAscii(ENTRIES[i][1]), QpackStaticTable.value(i));
		}
	}

	@Test
	public void nameIndexResolvesToLowestMatchingIndex() {
		// ":method" first appears at index 15 (CONNECT)
		assertEquals(15, QpackStaticTable.indexOfName(HttpHeaders.of(":method")));
		// "content-type" first appears at index 44
		assertEquals(44, QpackStaticTable.indexOfName(HttpHeaders.of("content-type")));
		assertEquals(-1, QpackStaticTable.indexOfName(HttpHeaders.of("x-not-in-static-table")));
	}

	@Test
	public void nameValueIndexResolvesExactPairs() {
		assertEquals(17, QpackStaticTable.indexOfNameAndValue(HttpHeaders.of(":method"), encodeAscii("GET")));
		assertEquals(20, QpackStaticTable.indexOfNameAndValue(HttpHeaders.of(":method"), encodeAscii("POST")));
		assertEquals(25, QpackStaticTable.indexOfNameAndValue(HttpHeaders.of(":status"), encodeAscii("200")));
		// Right name, wrong value -> no match
		assertEquals(-1, QpackStaticTable.indexOfNameAndValue(HttpHeaders.of(":method"), encodeAscii("PATCH")));
	}
}
