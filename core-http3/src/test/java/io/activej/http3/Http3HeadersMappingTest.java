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

package io.activej.http3;

import io.activej.http.HttpHeaders;
import io.activej.http.HttpMessages;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import io.activej.http.Protocol;
import org.junit.Test;

import java.util.List;

import static io.activej.http3.Http3Headers.Field;
import static org.junit.Assert.*;

/**
 * Pseudo-header <-> {@link HttpRequest}/{@link HttpResponse} mapping (FR-035, FR-036).
 */
public class Http3HeadersMappingTest {

	@Test
	public void requestFieldSectionMapsToHttpRequest() throws Http3Exception {
		List<Field> fields = List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com"),
			new Field(":path", "/foo?bar"),
			new Field("user-agent", "test"));

		HttpRequest request = Http3Headers.toRequestBuilder(fields).build();

		assertEquals(HttpVersion.HTTP_3_0, request.getVersion());
		assertEquals(HttpMethod.GET, request.getMethod());
		assertEquals(Protocol.HTTPS, request.getProtocol());
		assertEquals("/foo", request.getPath());
		assertEquals("bar", request.getQuery());
		assertEquals("example.com", request.getHeader(HttpHeaders.HOST));
		assertEquals("test", request.getHeader(HttpHeaders.USER_AGENT));
	}

	@Test
	public void requestWithoutAuthorityFallsBackToRegularHostField() throws Http3Exception {
		List<Field> fields = List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "http"),
			new Field(":path", "/"),
			new Field("host", "fallback.example"));

		HttpRequest request = Http3Headers.toRequestBuilder(fields).build();

		assertEquals("fallback.example", request.getHeader(HttpHeaders.HOST));
		assertEquals(Protocol.HTTP, request.getProtocol());
	}

	@Test
	public void responseMapsToFieldSectionWithStatusFirstAndNamesLowercased() {
		HttpResponse response = HttpMessages.responseBuilder(HttpVersion.HTTP_3_0, 200)
			.withHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
			.build();

		List<Field> fields = Http3Headers.fromResponse(response);

		assertEquals(new Field(":status", "200"), fields.get(0));
		assertEquals(new Field("content-type", "text/plain"), fields.get(1));
	}

	@Test
	public void responseFieldSectionMapsToHttpResponse() throws Http3Exception {
		List<Field> fields = List.of(
			new Field(":status", "404"),
			new Field("content-type", "text/plain"));

		HttpResponse response = Http3Headers.toResponseBuilder(fields).build();

		assertEquals(HttpVersion.HTTP_3_0, response.getVersion());
		assertEquals(404, response.getCode());
		assertEquals("text/plain", response.getHeader(HttpHeaders.CONTENT_TYPE));
	}

	@Test
	public void requestMapsToFieldSectionWithPseudoHeadersFirstAndNamesLowercased() {
		HttpRequest request = HttpMessages.requestBuilder(HttpVersion.HTTP_3_0, HttpMethod.POST, "https://example.com/foo?bar")
			.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
			.build();

		List<Field> fields = Http3Headers.fromRequest(request);

		assertEquals(new Field(":method", "POST"), fields.get(0));
		assertEquals(new Field(":scheme", "https"), fields.get(1));
		assertEquals(new Field(":authority", "example.com"), fields.get(2));
		assertEquals(new Field(":path", "/foo?bar"), fields.get(3));
		assertTrue(fields.contains(new Field("content-type", "application/json")));
	}
}
