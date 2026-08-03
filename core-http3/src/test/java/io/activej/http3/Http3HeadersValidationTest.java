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

import io.activej.bytebuf.ByteBuf;
import io.activej.http.HttpHeaders;
import io.activej.http3.qpack.QpackException;
import io.activej.http3.qpack.QpackField;
import io.activej.http3.qpack.QpackStaticDecoder;
import io.activej.http3.qpack.QpackStaticEncoder;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static io.activej.http3.Http3Headers.Field;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9114 §4.1.2/§4.2 field-list validation (FR-034, FR-037, FR-038). Every violation here
 * raises exactly {@code H3_MESSAGE_ERROR} — a request-stream reset, never a connection error.
 */
public class Http3HeadersValidationTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

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

	// ---------------------------------------------------------------- the QPACK (wire) path

	/**
	 * RFC 9114 §4.1.1 where it actually has to hold: on a field name that arrived <b>over the wire</b>,
	 * through a real QPACK decode, rather than on a {@code List<Field>} assembled by hand. The check used
	 * to run after {@code fromQpack} had already lowercased everything, so it could never fire here.
	 */
	@Test
	public void qpackDecodedUppercaseFieldNameIsRejected() {
		// Encoded by this module's own encoder: "x-custom-name" is in neither the QPACK static table nor
		// core-http's registry, so it goes on the wire as a Literal Field Line with Literal Name, spelled
		// exactly as given.
		Http3Exception e = assertThrows(Http3Exception.class, () -> fromQpack(
			new QpackField(HttpHeaders.of("X-Custom-Name"), encodeAscii("value"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void qpackDecodedUppercasePseudoHeaderNameIsRejected() {
		// Hand-built rather than encoded: our own encoder resolves ":path" to a static-table name
		// reference, which carries no name octets at all. A hostile peer is under no such obligation.
		Http3Exception e = assertThrows(Http3Exception.class,
			() -> fromWire(literalFieldSection(":Path", "/")));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void qpackDecodedIllegalOctetInFieldNameIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class,
			() -> fromWire(literalFieldSection("x y", "v")));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	/**
	 * The other half of the rule above, and the reason it cannot simply reject every uppercase octet
	 * {@code fromQpack} sees: {@code content-length} is registered in {@code core-http}, so interning
	 * hands the decoder's output back as {@code Content-Length} whatever the wire spelled. That case is
	 * normalized, and the two names whose received spelling <i>did</i> survive are passed through as they
	 * arrived.
	 */
	@Test
	public void qpackDecodedLowercaseFieldNamesAreAccepted() throws Http3Exception {
		List<Field> fields = fromQpack(
			new QpackField(HttpHeaders.of(":method"), encodeAscii("GET")),
			new QpackField(HttpHeaders.of("x-custom-name"), encodeAscii("value")),
			new QpackField(HttpHeaders.of("content-length"), encodeAscii("0")));
		assertEquals(List.of(":method", "x-custom-name", "content-length"), fields.stream().map(Field::name).toList());
	}

	/** Field names this module encodes itself must survive its own decoder — no round trip may be rejected. */
	@Test
	public void encodedRequestFieldsRoundTripThroughQpack() throws Http3Exception {
		List<Field> sent = validRequest(new Field("user-agent", "activej/6"), new Field("content-length", "0"));
		List<Field> received = fromQpack(Http3Headers.toQpack(sent).toArray(new QpackField[0]));
		assertEquals(sent.stream().map(Field::name).toList(), received.stream().map(Field::name).toList());
		Http3Headers.toRequestBuilder(received).build();
	}

	private static List<Field> fromQpack(QpackField... fields) throws Http3Exception {
		return fromWire(new QpackStaticEncoder().encode(List.of(fields)));
	}

	private static List<Field> fromWire(byte[] fieldSection) throws Http3Exception {
		return fromWire(ByteBuf.wrapForReading(fieldSection));
	}

	/** The decoder owns and recycles {@code fieldSection} on every path, so nothing here has to. */
	private static List<Field> fromWire(ByteBuf fieldSection) throws Http3Exception {
		List<QpackField> decoded;
		try {
			decoded = new QpackStaticDecoder(Http3Settings.create().maxFieldSectionSize()).decode(fieldSection);
		} catch (QpackException e) {
			throw new AssertionError("the field section did not decode", e);
		}
		return Http3Headers.fromQpack(decoded);
	}

	/**
	 * One field section carrying a single Literal Field Line with Literal Name (RFC 9204 §4.5.4), no
	 * Huffman — what a peer emits when it wants a name of its own choosing, byte for byte, on the wire.
	 */
	private static byte[] literalFieldSection(String name, String value) {
		byte[] nameBytes = encodeAscii(name);
		byte[] valueBytes = encodeAscii(value);
		assertTrue("both lengths must fit their prefix without a continuation byte",
			nameBytes.length < 7 && valueBytes.length < 127);

		byte[] section = new byte[2 + 1 + nameBytes.length + 1 + valueBytes.length];
		// section[0..1]: Encoded Field Section Prefix — Required Insert Count 0, S 0, Delta Base 0.
		int i = 2;
		section[i++] = (byte) (0x20 | nameBytes.length); // "0 0 1 N=0 H=0 name-len(3+)"
		System.arraycopy(nameBytes, 0, section, i, nameBytes.length);
		i += nameBytes.length;
		section[i++] = (byte) valueBytes.length;         // "H=0 value-len(7+)"
		System.arraycopy(valueBytes, 0, section, i, valueBytes.length);
		return section;
	}

	// ---------------------------------------------------------------- FR-063

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

	/**
	 * The response half of the rule above: {@code :status} is the one field value a <b>client</b> parses
	 * out of something a server chose, so its two rejection paths are held to the same standard.
	 */
	@Test
	public void exceptionMessagesNeverContainSuppliedStatusValues() {
		String secretNonNumericStatus = "2OO-tenant-4f21-s3cret";
		String secretOutOfRangeStatus = "9994f21";

		assertResponseMessageDoesNotContain(
			List.of(new Field(":status", secretNonNumericStatus)), secretNonNumericStatus);
		assertResponseMessageDoesNotContain(
			List.of(new Field(":status", secretOutOfRangeStatus)), secretOutOfRangeStatus);
	}

	/**
	 * A field <b>name</b> is peer-supplied bytes too on the QPACK path — and, unlike a value, one that
	 * has just been found to be outside the token set, so echoing it is how a newline the peer chose
	 * would reach a log line.
	 */
	@Test
	public void exceptionMessagesNeverContainSuppliedFieldNames() {
		String secretUppercaseName = "x-tenant-4f21-Internal";
		String secretIllegalName = "x-tenant-4f21\nset-cookie: injected";
		String secretPseudoHeader = ":tenant-4f21";

		assertMessageDoesNotContain(validRequest(new Field(secretUppercaseName, "v")), secretUppercaseName);
		assertMessageDoesNotContain(validRequest(new Field(secretIllegalName, "v")), secretIllegalName);
		assertMessageDoesNotContain(validRequest(new Field(secretPseudoHeader, "v")), secretPseudoHeader);

		assertMessageDoesNotContain(
			List.of(new Field(":method", "GET"), new Field("user-agent", "test"), new Field(secretPseudoHeader, "v")),
			secretPseudoHeader);

		Http3Exception e = assertThrows(Http3Exception.class,
			() -> Http3Headers.validateTrailers(List.of(new Field(secretPseudoHeader, "v"))));
		assertNoSecretIn(e.getMessage(), secretPseudoHeader);
	}

	private static void assertMessageDoesNotContain(List<Field> fields, String... secrets) {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(fields));
		assertNoSecretIn(e.getMessage(), secrets);
	}

	private static void assertResponseMessageDoesNotContain(List<Field> fields, String... secrets) {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toResponseBuilder(fields));
		assertNoSecretIn(e.getMessage(), secrets);
	}

	private static void assertNoSecretIn(String message, String... secrets) {
		for (String secret : secrets) {
			assertFalse(
				"exception message must not leak the peer-supplied \"" + secret + "\", but was: " + message,
				message.contains(secret));
		}
	}
}
