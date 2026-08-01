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

import org.junit.Test;

import java.util.List;

import static io.activej.http3.Http3Headers.Field;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * RFC 9114 §4.1.2/§4.2 field-list validation (FR-034, FR-037, FR-038). Every violation here
 * raises exactly {@code H3_MESSAGE_ERROR} — a request-stream reset, never a connection error.
 */
public class Http3HeadersValidationTest {

	private static List<Field> validRequest(Field... extra) {
		java.util.ArrayList<Field> fields = new java.util.ArrayList<>(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com"),
			new Field(":path", "/")));
		fields.addAll(List.of(extra));
		return fields;
	}

	private static void assertMessageError(List<Field> fields) {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(fields));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void uppercaseOctetInFieldNameIsRejected() {
		assertMessageError(validRequest(new Field("User-Agent", "test")));
	}

	@Test
	public void uppercaseOctetInPseudoHeaderNameIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":Method", "GET"),
			new Field(":scheme", "https"),
			new Field(":path", "/"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void illegalOctetInFieldNameIsRejected() {
		// Space is outside the RFC 9110 tchar set.
		assertMessageError(validRequest(new Field("user agent", "test")));
	}

	@Test
	public void pseudoHeaderAfterARegularFieldIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field("user-agent", "test"),
			new Field(":scheme", "https"),
			new Field(":path", "/"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void unknownPseudoHeaderIsRejected() {
		assertMessageError(validRequest(new Field(":bogus", "1")));
	}

	@Test
	public void duplicatedPseudoHeaderIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":method", "POST"),
			new Field(":scheme", "https"),
			new Field(":path", "/"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void missingMethodOnARequestIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":scheme", "https"),
			new Field(":path", "/"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void missingSchemeOnARequestIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":path", "/"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void missingPathOnARequestIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void emptyPathOnARequestIsRejected() {
		// FR-035: ":path" MUST be non-empty for the http/https schemes — distinct from the
		// pseudo-header being entirely absent (missingPathOnARequestIsRejected above).
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com"),
			new Field(":path", ""))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void missingStatusOnAResponseIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class,
			() -> Http3Headers.toResponseBuilder(List.of(new Field("content-type", "text/plain"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void connectionSpecificFieldsAreRejected() {
		for (String name : new String[] {"connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade"}) {
			assertMessageError(validRequest(new Field(name, "x")));
		}
	}

	@Test
	public void teFieldMustBeExactlyTrailers() {
		assertMessageError(validRequest(new Field("te", "gzip")));
	}

	@Test
	public void teFieldWithExactlyTrailersIsAccepted() throws Http3Exception {
		// Does not throw.
		Http3Headers.toRequestBuilder(validRequest(new Field("te", "trailers"))).build();
	}

	@Test
	public void pseudoHeaderInATrailerSectionIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class,
			() -> Http3Headers.validateTrailers(List.of(new Field(":status", "200"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void regularTrailerFieldsAreAccepted() throws Http3Exception {
		// Does not throw.
		Http3Headers.validateTrailers(List.of(new Field("x-checksum", "abc123")));
	}

	// FR-063: no field value may reach an exception message — a caller logging a caught
	// Http3Exception must never leak :authority/:path/:method/TE data supplied by the peer.
	@Test
	public void exceptionMessagesNeverContainSuppliedFieldValues() {
		String secretAuthority = "internal-secret-host.example";
		String secretPath = "/accounts?token=super-secret-token";
		String secretMethod = "S3cr3tVerb";
		String secretTeValue = "not-trailers-s3cret";

		assertMessageDoesNotContain(
			List.of(new Field(":method", secretMethod), new Field(":scheme", "https"),
				new Field(":authority", secretAuthority), new Field(":path", secretPath)),
			secretMethod, secretAuthority, secretPath);

		assertMessageDoesNotContain(
			List.of(new Field(":method", "GET"), new Field(":scheme", "not a valid scheme"),
				new Field(":authority", secretAuthority), new Field(":path", secretPath)),
			secretAuthority, secretPath);

		assertMessageDoesNotContain(
			validRequest(new Field("te", secretTeValue)), secretTeValue);
	}

	private static void assertMessageDoesNotContain(List<Field> fields, String... secrets) {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(fields));
		String message = e.getMessage();
		for (String secret : secrets) {
			org.junit.Assert.assertFalse(
				"exception message must not leak the supplied field value \"" + secret + "\", but was: " + message,
				message.contains(secret));
		}
	}
}
