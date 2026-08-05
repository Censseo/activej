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

	// ---------------------------------------------------------------- T119: singleton regular fields

	/**
	 * RFC 9110 §8.6: two {@code Content-Length} values that disagree make the message invalid. Only the
	 * first was ever reconciled against the DATA that arrived, and for a gateway re-serializing to
	 * HTTP/1.1 the pair is a request-smuggling primitive — the next hop may well take the last.
	 */
	@Test
	public void twoDisagreeingContentLengthFieldsAreRejected() {
		assertMessageError(validRequest(new Field("content-length", "5"), new Field("content-length", "9")));
	}

	/** The RFC 9110 §8.6 list form: repeated and <i>identical</i> says nothing new, and is legal. */
	@Test
	public void twoIdenticalContentLengthFieldsAreAccepted() throws Http3Exception {
		Http3Headers.toRequestBuilder(
			validRequest(new Field("content-length", "5"), new Field("content-length", "5"))).build();
	}

	/** RFC 9110 §7.2: exactly one {@code Host}, whichever of the two the receiver would have picked. */
	@Test
	public void twoHostFieldsAreRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":path", "/"),
			new Field("host", "example.com"),
			new Field("host", "evil.test"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	/** Identical is not an exemption here the way it is for Content-Length: §7.2 says one, not one value. */
	@Test
	public void twoIdenticalHostFieldsAreRejected() {
		assertMessageError(validRequest(new Field("host", "example.com"), new Field("host", "example.com")));
	}

	/** A duplicate of any other field is ordinary HTTP and stays that way. */
	@Test
	public void aRepeatedOrdinaryFieldIsAccepted() throws Http3Exception {
		Http3Headers.toRequestBuilder(
			validRequest(new Field("accept", "text/plain"), new Field("accept", "text/html"))).build();
	}

	// ---------------------------------------------------------------- T120: request target and field values

	/**
	 * RFC 9114 §4.3.1: under a scheme with a mandatory authority component, {@code :path} is origin-form.
	 * Without the check the assembled URL silently changes meaning — {@code :path: x} makes
	 * {@code https://example.com} into {@code https://example.comx}, an authority nobody named.
	 */
	@Test
	public void aPathNotBeginningWithASlashIsRejected() {
		assertMessageError(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com"),
			new Field(":path", "x")));
	}

	/** The same rule under {@code http}, and it is the scheme that decides — not the presence of a path. */
	@Test
	public void aRelativePathUnderHttpIsRejected() {
		assertMessageError(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "http"),
			new Field(":authority", "example.com"),
			new Field(":path", "index.html")));
	}

	/**
	 * The section's one exception, {@code OPTIONS *}. {@code core-http}'s {@code UrlParser} has no
	 * representation for the asterisk form, so it is refused as an unsupported capability — retryable,
	 * the way CONNECT and {@code :protocol} are — rather than mangled into an origin-form URL.
	 */
	@Test
	public void theAsteriskFormIsRefusedAsUnsupportedRatherThanMangled() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "OPTIONS"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com"),
			new Field(":path", "*"))));
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, e.errorCode());
		assertTrue("nothing is wrong with the message, so re-issuing it elsewhere is safe", e.isRetryable());
	}

	/**
	 * A scheme this class knows no authority rule for gets no invented path rule either — RFC 9114 §4.3.1
	 * constrains the path only where the scheme constrains the authority. Such a request is still refused,
	 * because {@code core-http}'s {@code UrlParser} understands {@code http} and {@code https} and nothing
	 * else; what this asserts is <b>which</b> rule refuses it, since only one of the two would still be
	 * right if that ever changed.
	 */
	@Test
	public void aPathUnderAnUnknownSchemeIsNotJudgedByThePathRule() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "ftp"),
			new Field(":authority", "example.com"),
			new Field(":path", "relative"))));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
		assertTrue("refused by the URL parse, not by the origin-form rule: " + e.reason(),
			e.reason().contains("Malformed request target"));
	}

	/**
	 * RFC 9114 §4.1.2: NUL, CR and LF are forbidden at any position in a field value. HTTP/3 has no
	 * line-oriented framing, so carrying them costs this layer nothing — but the value reaches a servlet,
	 * and the hop after that may be HTTP/1.1, where the same octets split a response.
	 */
	@Test
	public void controlOctetsInAFieldValueAreRejected() {
		for (String value : new String[] {"a\rb", "a\nb", "a" + (char) 0 + "b", "trailing\r\n", "\nleading"}) {
			assertMessageError(validRequest(new Field("x-note", value)));
		}
	}

	/** The same rule applies to a pseudo-header value, which is where a request target comes from. */
	@Test
	public void controlOctetsInAPseudoHeaderValueAreRejected() {
		assertMessageError(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com\r\nx-injected: 1"),
			new Field(":path", "/")));
	}

	/** RFC 9114 §4.1.2, the other half: no leading or trailing SP/HTAB either. */
	@Test
	public void surroundingWhitespaceInAFieldValueIsRejected() {
		for (String value : new String[] {" leading", "trailing ", "\ttab", "tab\t"}) {
			assertMessageError(validRequest(new Field("x-note", value)));
		}
	}

	/** Whitespace *inside* a value is ordinary and stays legal — only the edges are the RFC's concern. */
	@Test
	public void interiorWhitespaceInAFieldValueIsAccepted() throws Http3Exception {
		Http3Headers.toRequestBuilder(validRequest(new Field("x-note", "two words"))).build();
	}

	/** An empty value is legal HTTP, and has no edge to be whitespace. */
	@Test
	public void anEmptyFieldValueIsAccepted() throws Http3Exception {
		Http3Headers.toRequestBuilder(validRequest(new Field("x-note", ""))).build();
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
		// Hand-built rather than encoded: QpackStaticEncoder now lowercases every literal name it writes
		// (RFC 9114 §4.1.1, QpackField.lowercaseNameBytes), so this module can no longer produce the bytes
		// under test. A peer is under no such obligation, which is why the receive side still checks.
		Http3Exception e = assertThrows(Http3Exception.class,
			() -> fromWire(literalFieldSection("X-Note", "value")));
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
	 * The case interning used to hide, and the T108 residual this closes.
	 * <p>
	 * {@code date} is registered in {@code core-http}, so {@code HttpHeaders.of} hands back the same
	 * token whether the wire spelled it {@code date} or {@code Date} — by the time {@code fromQpack} sees
	 * an {@link HttpHeader} the peer's own spelling is gone, and the check could only conclude "the
	 * registry canonicalised this" and let it through. RFC 9114 §4.1.1 requires the uppercase form to be
	 * rejected, so the decoder now records what the octets said and {@code fromQpack} acts on that.
	 * <p>
	 * Goes through a real wire decode on purpose: a hand-built {@code QpackField} cannot reproduce this,
	 * because the fact under test is one only the decoder ever holds.
	 */
	@Test
	public void qpackDecodedUppercaseRegisteredFieldNameIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class,
			() -> fromWire(literalFieldSection("Date", "x")));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	/** The same name, spelled as RFC 9114 §4.1.1 requires, still decodes. */
	@Test
	public void qpackDecodedLowercaseRegisteredFieldNameIsAccepted() throws Http3Exception {
		assertEquals(List.of("date"), fromWire(literalFieldSection("date", "x")).stream().map(Field::name).toList());
	}

	/**
	 * A field list assembled by hand carries no wire provenance, so the interning-aware path still applies:
	 * a registered name reaching {@code fromQpack} as its canonical token is normalized, not rejected.
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
