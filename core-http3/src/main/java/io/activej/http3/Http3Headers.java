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

import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaderValue;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMessages;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import io.activej.http.Protocol;
import io.activej.http3.qpack.QpackField;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pseudo-header mapping between a decoded HTTP/3 field list and {@code core-http}'s
 * {@link HttpRequest}/{@link HttpResponse}, plus the RFC 9114 §4.1.2/§4.2 validation every field
 * list must pass first.
 * <p>
 * <b>Field-list representation</b>: this phase has no raw QPACK-decoded bytes flowing through it
 * — the {@code io.activej.http3.qpack} decoder is built by a parallel effort, and the reactive
 * layer that threads its output through here is blocked on feature 04. A {@link Field} is
 * therefore a plain, already-materialized {@code (String name, String value)} pair rather than
 * anything QPACK-shaped; the reactive layer's job is to adapt whatever the QPACK decoder produces
 * into a {@code List<Field>} (or to call {@link HttpHeaders#of(int, byte[], int, int)} directly
 * against the interned constants this class also uses, if it wants to skip an intermediate
 * {@code String} — nothing here forecloses that).
 * <p>
 * Field names arriving here are expected already lowercase-or-rejected per RFC 9114 §4.1.1 — the
 * validation in this class enforces that, it does not silently normalize case.
 */
public final class Http3Headers {
	private Http3Headers() {
	}

	/** One decoded HTTP/3 field: a pseudo-header ({@code ":method"}, …) or a regular field. */
	public record Field(String name, String value) {
		public Field {
			if (name == null || value == null) {
				throw new NullPointerException("Http3Headers.Field name/value must not be null");
			}
		}
	}

	private static final String METHOD = ":method";
	private static final String SCHEME = ":scheme";
	private static final String AUTHORITY = ":authority";
	private static final String PATH = ":path";
	private static final String STATUS = ":status";

	/**
	 * The RFC 9220 extended-CONNECT pseudo-header — what turns a CONNECT into a WebSocket handshake or
	 * another tunnelled protocol. Out of scope (FR-040), and named here so that it can be refused as the
	 * unsupported <i>capability</i> it is rather than falling through to "unknown pseudo-header".
	 */
	private static final String PROTOCOL = ":protocol";

	private static final Set<String> REQUEST_PSEUDO_HEADERS = Set.of(METHOD, SCHEME, AUTHORITY, PATH);
	private static final Set<String> RESPONSE_PSEUDO_HEADERS = Set.of(STATUS);

	// RFC 9114 §4.2 — fields whose semantics are specific to a single hop-by-hop HTTP/1.1/2
	// connection and therefore have no meaning in HTTP/3, which has none.
	private static final Set<String> CONNECTION_SPECIFIC_FIELDS =
		Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade");

	private static final String TE_FIELD = "te";
	private static final String TE_ALLOWED_VALUE = "trailers";

	private static final String HOST_FIELD = "host";
	private static final String CONTENT_LENGTH_FIELD = "content-length";

	/**
	 * The two regular fields a message may carry at most once, and the reason each is more than a
	 * tidiness rule. RFC 9110 §8.6 makes differing {@code Content-Length} values invalid and §7.2 permits
	 * exactly one {@code Host}; the receiver of a duplicate has to pick one, and for a gateway that
	 * re-serializes to HTTP/1.1 the pair is a request-smuggling primitive — this side reconciles the
	 * first, the next hop may well take the last.
	 */
	private static final Set<String> SINGLETON_FIELDS = Set.of(HOST_FIELD, CONTENT_LENGTH_FIELD);

	/** The schemes RFC 9114 §4.3.1 gives a mandatory authority component, and hence a path shape. */
	private static final Set<String> AUTHORITY_SCHEMES = Set.of("http", "https");

	/**
	 * RFC 9114 §4.3.1's one exception to "{@code :path} begins with {@code /}": an OPTIONS request that
	 * applies to the server rather than to a resource. {@code core-http}'s {@code UrlParser} has no
	 * representation for the asterisk form, so it is refused as an unsupported capability rather than
	 * mangled into an origin-form URL — see {@link #requestRejected}.
	 */
	private static final String ASTERISK_FORM = "*";

	private enum Mode {REQUEST, RESPONSE, TRAILERS}

	// region validation

	/**
	 * Validates a trailing HEADERS field list (RFC 9114 §4.1): no pseudo-header may appear in a
	 * trailer section at all.
	 *
	 * @throws Http3Exception {@code H3_MESSAGE_ERROR} on any violation
	 */
	public static void validateTrailers(List<Field> fields) throws Http3Exception {
		collectAndValidate(fields, Mode.TRAILERS, new ArrayList<>());
	}

	/**
	 * Validates then maps a request field list into an {@link HttpRequest.Builder} (FR-035,
	 * FR-034). {@code :authority} — if present, else the regular {@code host} field — is copied
	 * onto a {@code Host} header, matching the shape a server-parsed HTTP/1.1 request already has
	 * ({@code core-http}'s {@code HttpUtils.getFullUri} reads {@code Host} for exactly this case),
	 * so an {@link io.activej.http.AsyncServlet} sees an equivalent request either way.
	 * <p>
	 * CONNECT, and any request carrying RFC 9220's {@code :protocol} pseudo-header, is refused here with
	 * {@code H3_REQUEST_REJECTED} — see {@link #requestRejected} for why that code and not the usual one
	 * (FR-040).
	 *
	 * @throws Http3Exception {@code H3_MESSAGE_ERROR} on any validation failure, a missing
	 *                        required pseudo-header, an unknown {@code :method}, or a
	 *                        {@code :scheme}/{@code :authority}/{@code :path} combination that
	 *                        does not form a valid request target; {@code H3_REQUEST_REJECTED} on a
	 *                        CONNECT request or an {@code :protocol} pseudo-header
	 */
	public static HttpRequest.Builder toRequestBuilder(List<Field> fields) throws Http3Exception {
		List<Field> regular = new ArrayList<>();
		Map<String, String> pseudo = collectAndValidate(fields, Mode.REQUEST, regular);

		String methodValue = pseudo.get(METHOD);
		String scheme = pseudo.get(SCHEME);
		String path = pseudo.get(PATH);
		if (methodValue == null || scheme == null || path == null || path.isEmpty()) {
			throw messageError("Missing or empty required pseudo-header on a request (:method, :scheme and :path are all required, :path must be non-empty)");
		}

		HttpMethod method;
		try {
			method = HttpMethod.valueOf(methodValue);
		} catch (IllegalArgumentException e) {
			throw messageError("Unknown :method value");
		}
		if (method == HttpMethod.CONNECT) {
			// FR-040: CONNECT is a tunnel, and this implementation has none to offer. Refused whole rather
			// than partially handled, and refused as unsupported rather than as malformed.
			throw requestRejected("The CONNECT method is not supported over HTTP/3");
		}

		validatePathShape(scheme, path);

		String authority = pseudo.get(AUTHORITY);
		if (authority == null) {
			for (Field field : regular) {
				if (field.name().equals(HOST_FIELD)) {
					authority = field.value();
					break;
				}
			}
		}

		String url = scheme + "://" + (authority != null ? authority : "") + path;
		HttpRequest.Builder builder;
		try {
			builder = HttpMessages.requestBuilder(HttpVersion.HTTP_3_0, method, url);
		} catch (IllegalArgumentException e) {
			throw messageError("Malformed request target (:scheme/:authority/:path do not form a valid URL)");
		}

		if (authority != null) {
			builder.withHeader(HttpHeaders.HOST, authority);
		}
		for (Field field : regular) {
			if (field.name().equals(HOST_FIELD)) continue;
			builder.withHeader(HttpHeaders.of(field.name()), field.value());
		}
		return builder;
	}

	/**
	 * RFC 9114 §4.3.1: under a scheme with a mandatory authority component, {@code :path} is an
	 * origin-form path and therefore begins with {@code /}. Without this check the URL assembled below
	 * silently changes meaning — {@code :path: x} makes {@code https://example.com} into
	 * {@code https://example.comx}, an authority the peer never named, and one a servlet reading
	 * {@code Host} would not agree with either.
	 * <p>
	 * The section's one exception is the asterisk form, {@code OPTIONS *}. {@code core-http}'s
	 * {@code UrlParser} has no representation for it — there is no origin-form URL that means "the server
	 * itself" — so it is refused as an unsupported capability, the way CONNECT and {@code :protocol} are
	 * (FR-040's shape, not its scope): nothing is wrong with the message, and a peer is told exactly
	 * that rather than being handed a mangled request target.
	 * <p>
	 * A scheme outside {@link #AUTHORITY_SCHEMES} is left alone: the RFC constrains the path only where
	 * the scheme constrains the authority, and this class has no business inventing a rule for a scheme
	 * it does not know.
	 */
	private static void validatePathShape(String scheme, String path) throws Http3Exception {
		if (!AUTHORITY_SCHEMES.contains(scheme)) return;
		if (path.charAt(0) == '/') return;
		if (path.equals(ASTERISK_FORM)) {
			throw requestRejected("The asterisk form of :path (OPTIONS *) is not supported over HTTP/3");
		}
		throw messageError("A :path under a scheme with a mandatory authority must begin with '/' (RFC 9114 §4.3.1)");
	}

	/**
	 * Whether this response field list carries an informational ({@code 1xx}) {@code :status} — a purely
	 * syntactic scan whose <b>only</b> job is to route between {@link #validateInterimResponse} and
	 * {@link #toResponseBuilder}, so that every field list is fully validated exactly once, by whichever
	 * of the two it goes to.
	 * <p>
	 * Deliberately answers {@code false} for a {@code :status} that is missing or not three digits: that
	 * is not an interim response, it is a malformed one, and {@link #toResponseBuilder} is where the
	 * message-error rules live. Nothing here rejects anything.
	 */
	public static boolean isInformationalStatus(List<Field> fields) {
		for (Field field : fields) {
			if (!field.name().equals(STATUS)) continue;
			String value = field.value();
			return value.length() == 3 && value.charAt(0) == '1';
		}
		return false;
	}

	/**
	 * Validates an informational ({@code 1xx}) response's field list without building a message from it:
	 * RFC 9114 §4.1 has a client consume an interim response and go on reading for the final one, so
	 * there is nothing here for a caller to receive — but the section is still a response field section,
	 * and a malformed one is still {@code H3_MESSAGE_ERROR}.
	 *
	 * @throws Http3Exception {@code H3_MESSAGE_ERROR} on any validation failure, exactly as
	 *                        {@link #toResponseBuilder} raises it
	 */
	public static void validateInterimResponse(List<Field> fields) throws Http3Exception {
		collectAndValidate(fields, Mode.RESPONSE, new ArrayList<>());
	}

	/**
	 * Validates then maps a response field list into an {@link HttpResponse.Builder} (FR-035).
	 *
	 * @throws Http3Exception {@code H3_MESSAGE_ERROR} on any validation failure, a missing
	 *                        {@code :status}, or a {@code :status} that is not a valid 3-digit
	 *                        status code
	 */
	public static HttpResponse.Builder toResponseBuilder(List<Field> fields) throws Http3Exception {
		List<Field> regular = new ArrayList<>();
		Map<String, String> pseudo = collectAndValidate(fields, Mode.RESPONSE, regular);

		String statusValue = pseudo.get(STATUS);
		if (statusValue == null) {
			throw messageError("Missing required :status pseudo-header on a response");
		}
		int code;
		try {
			code = Integer.parseInt(statusValue);
		} catch (NumberFormatException e) {
			// FR-063: the rule that was violated, never the value that violated it — a peer chose this
			// string, and a caller logging the exception would publish it. Same shape as ":method" above.
			throw messageError("Non-numeric :status value");
		}
		if (code < 100 || code >= 600) {
			throw messageError("Out-of-range :status value");
		}

		HttpResponse.Builder builder = HttpMessages.responseBuilder(HttpVersion.HTTP_3_0, code);
		for (Field field : regular) {
			builder.withHeader(HttpHeaders.of(field.name()), field.value());
		}
		return builder;
	}

	/**
	 * FR-063 note for everything that throws below: a field <b>name</b> is peer-supplied bytes on the
	 * QPACK path just as a value is, so a reason never interpolates one either — except where the name
	 * is provably a member of a closed set of compile-time constants by the time it is named
	 * ({@link #CONNECTION_SPECIFIC_FIELDS}, and the already-matched pseudo-header of the duplicate
	 * check), which carries no peer bytes at all.
	 */
	private static Map<String, String> collectAndValidate(List<Field> fields, Mode mode, List<Field> regularOut) throws Http3Exception {
		Map<String, String> pseudo = new LinkedHashMap<>();
		boolean seenRegular = false;
		for (Field field : fields) {
			String name = field.name();
			validateFieldName(name);
			validateFieldValue(field.value());
			if (!name.isEmpty() && name.charAt(0) == ':') {
				if (mode == Mode.TRAILERS) {
					throw messageError("Pseudo-header in a trailer section");
				}
				if (seenRegular) {
					throw messageError("Pseudo-header after a regular field");
				}
				if (mode == Mode.REQUEST && name.equals(PROTOCOL)) {
					// FR-040, ahead of the unknown-pseudo-header rule below: the field list is well formed,
					// and what it asks for is a capability this implementation does not have.
					throw requestRejected("Extended CONNECT (the :protocol pseudo-header, RFC 9220) is not supported");
				}
				Set<String> allowed = mode == Mode.REQUEST ? REQUEST_PSEUDO_HEADERS : RESPONSE_PSEUDO_HEADERS;
				if (!allowed.contains(name)) {
					throw messageError("Unknown pseudo-header");
				}
				// Past the check above, `name` is one of REQUEST_PSEUDO_HEADERS/RESPONSE_PSEUDO_HEADERS —
				// a constant of this class, not anything the peer chose, so naming it leaks nothing.
				if (pseudo.containsKey(name)) {
					throw messageError("Duplicated pseudo-header: " + name);
				}
				pseudo.put(name, field.value());
			} else {
				seenRegular = true;
				if (CONNECTION_SPECIFIC_FIELDS.contains(name)) {
					throw messageError("Connection-specific field is not permitted in HTTP/3: " + name);
				}
				if (name.equals(TE_FIELD) && !field.value().equals(TE_ALLOWED_VALUE)) {
					throw messageError("TE field carries a value other than \"trailers\"");
				}
				checkSingleton(field, regularOut);
				regularOut.add(field);
			}
		}
		return pseudo;
	}

	/**
	 * Refuses a second {@code host}, and a second {@code content-length} that disagrees with the first
	 * (RFC 9110 §7.2 and §8.6). A repeated <i>identical</i> {@code Content-Length} is the list form §8.6
	 * explicitly permits, and says nothing new — it is accepted, and the duplicate carried through as
	 * any other repeated field is.
	 * <p>
	 * Linear over what has been collected so far, which is what the message-size bound already caps; a
	 * map would be a second index over a list that is nearly always under a dozen entries.
	 */
	private static void checkSingleton(Field field, List<Field> collectedSoFar) throws Http3Exception {
		String name = field.name();
		if (!SINGLETON_FIELDS.contains(name)) return;
		for (Field earlier : collectedSoFar) {
			if (!earlier.name().equals(name)) continue;
			if (name.equals(CONTENT_LENGTH_FIELD) && earlier.value().equals(field.value())) return;
			// `name` is a member of SINGLETON_FIELDS by the check above — a constant of this class, not
			// peer bytes — so naming it here carries nothing of the peer's (FR-063).
			throw messageError("Repeated " + name + " field");
		}
	}

	private static void validateFieldName(String name) throws Http3Exception {
		if (name.isEmpty()) {
			throw messageError("Empty field name");
		}
		int start = name.charAt(0) == ':' ? 1 : 0;
		if (start == 1 && name.length() == 1) {
			throw messageError("Empty pseudo-header name");
		}
		for (int i = start; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c >= 'A' && c <= 'Z') {
				throw messageError("Uppercase octet in a field name");
			}
			if (!isLowerTchar(c)) {
				// FR-063, and the sharpest case of it in this file: an illegal octet is by definition
				// outside the token set, so echoing the name here would put a control character (a
				// newline, most of all) the peer chose straight into a log line.
				throw messageError("Illegal octet in a field name");
			}
		}
	}

	/**
	 * RFC 9114 §4.1.2's field-value rules: no NUL, LF or CR at any position, and no leading or trailing
	 * SP/HTAB. Both halves matter beyond this process — HTTP/3 has no line-oriented framing, so a CR or
	 * LF here costs nothing to carry, but the value reaches a servlet and the hop after that may well be
	 * HTTP/1.1, where the same octets are a header-injection primitive. Applied to pseudo-header values
	 * as much as to regular ones: {@code :authority} and {@code :path} both end up in a request target.
	 * <p>
	 * FR-063 as everywhere else here: an illegal octet is by definition one that would damage the log
	 * line naming it, so the reason names the rule and the position of nothing.
	 */
	private static void validateFieldValue(String value) throws Http3Exception {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == 0 || c == '\n' || c == '\r') {
				throw messageError("Field value contains NUL, CR or LF (RFC 9114 §4.1.2)");
			}
		}
		if (!value.isEmpty() && (isSpOrHtab(value.charAt(0)) || isSpOrHtab(value.charAt(value.length() - 1)))) {
			throw messageError("Field value starts or ends with whitespace (RFC 9114 §4.1.2)");
		}
	}

	private static boolean isSpOrHtab(char c) {
		return c == ' ' || c == '\t';
	}

	// RFC 9110 §5.6.2 tchar, restricted to the lowercase half since uppercase is rejected above.
	private static boolean isLowerTchar(char c) {
		if (c >= 'a' && c <= 'z') return true;
		if (c >= '0' && c <= '9') return true;
		return switch (c) {
			case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~' -> true;
			default -> false;
		};
	}

	private static Http3Exception messageError(String detail) {
		return new Http3Exception(Http3Errors.H3_MESSAGE_ERROR, detail);
	}

	/**
	 * FR-040: a well-formed request asking for a capability this implementation does not have — CONNECT,
	 * or RFC 9220's extended CONNECT.
	 * <p>
	 * {@code H3_REQUEST_REJECTED} rather than the {@code H3_MESSAGE_ERROR} every other rejection here
	 * uses, and the difference is what the peer is being told: nothing is <i>wrong</i> with the message,
	 * so it was not processed and re-issuing it against an endpoint that does support the capability is
	 * safe. {@link Http3Exception} marks that code retryable of its own accord.
	 */
	private static Http3Exception requestRejected(String detail) {
		return new Http3Exception(Http3Errors.H3_REQUEST_REJECTED, detail);
	}

	// endregion

	// region encoding (outbound)

	/**
	 * Maps an {@link HttpResponse} to its outbound field list: {@code :status} first, then every
	 * header with its name lowercased (RFC 9114 §4.1.1/§4.2 requires lowercase on the wire;
	 * {@code core-http}'s {@link HttpHeader} preserves the case it was registered or received
	 * with, so this class lowercases on the way out rather than relying on the header token).
	 */
	public static List<Field> fromResponse(HttpResponse response) {
		List<Field> fields = new ArrayList<>();
		fields.add(new Field(STATUS, Integer.toString(response.getCode())));
		for (Map.Entry<HttpHeader, HttpHeaderValue> entry : response.getHeaders()) {
			fields.add(new Field(entry.getKey().toString().toLowerCase(Locale.ROOT), entry.getValue().toString()));
		}
		return fields;
	}

	/**
	 * Maps an {@link HttpRequest} to its outbound field list: {@code :method}, {@code :scheme},
	 * {@code :authority} (if known), {@code :path} first, then every remaining header with its
	 * name lowercased. The {@code Host} header, if present, is folded into {@code :authority}
	 * rather than also being sent as a regular field.
	 */
	public static List<Field> fromRequest(HttpRequest request) {
		List<Field> fields = new ArrayList<>();
		fields.add(new Field(METHOD, request.getMethod().name()));
		Protocol protocol = request.getProtocol();
		fields.add(new Field(SCHEME, protocol != null ? protocol.lowercase() : "https"));
		String authority = request.getHeader(HttpHeaders.HOST);
		if (authority == null) {
			authority = request.getHostAndPort();
		}
		if (authority != null) {
			fields.add(new Field(AUTHORITY, authority));
		}
		fields.add(new Field(PATH, request.getPathAndQuery()));
		for (Map.Entry<HttpHeader, HttpHeaderValue> entry : request.getHeaders()) {
			String name = entry.getKey().toString();
			if (name.equalsIgnoreCase("host")) continue;
			fields.add(new Field(name.toLowerCase(Locale.ROOT), entry.getValue().toString()));
		}
		return fields;
	}

	// endregion

	// region QPACK adaptation

	/**
	 * Adapts what a {@link io.activej.http3.qpack.QpackDecoder} produces into the {@link Field} list
	 * this class validates and maps. Field values are ISO-8859-1, the octet-per-character encoding
	 * {@code core-http} already reads header bytes as, so the round trip is byte-exact.
	 * <p>
	 * <b>Field-name case is checked here, against what the peer actually sent, before anything
	 * normalizes it</b> — RFC 9114 §4.1.1/FR-034, and a check run after normalization can never fire.
	 * <p>
	 * A {@link QpackField} carries an <i>interned</i> {@link HttpHeader}, and {@code core-http}'s
	 * registry is case-insensitive: for a name it has <i>registered</i> it hands back its own
	 * canonically-cased token — a {@code content-length} field line, however it was encoded, comes back
	 * out of the decoder as {@code Content-Length} — while for every other name it builds a fresh token
	 * holding the received bytes verbatim. {@link #isCanonicalizedByInterning} tells those two apart, so
	 * the uppercase rule is enforced exactly where the received case survived: every literal name
	 * outside that registry, pseudo-headers included.
	 * <p>
	 * <b>Residual gap, stated plainly</b>: an uppercase spelling of one of the names {@link HttpHeaders}
	 * pre-registers is still normalized rather than rejected, because past interning it is genuinely not
	 * observable. Closing that needs the raw name bytes, which only {@code QpackStaticDecoder} sees.
	 *
	 * @throws Http3Exception {@code H3_MESSAGE_ERROR} if a decoded field name is empty, carries an
	 *                        uppercase octet the peer itself chose, or carries an octet outside RFC
	 *                        9110's {@code tchar} set
	 */
	public static List<Field> fromQpack(List<QpackField> fields) throws Http3Exception {
		List<Field> result = new ArrayList<>(fields.size());
		for (QpackField field : fields) {
			result.add(new Field(
				decodedFieldName(field.name()),
				new String(field.value(), StandardCharsets.ISO_8859_1)));
		}
		return result;
	}

	/**
	 * The lowercase name of {@code header}, having enforced RFC 9114 §4.1.1 against whatever of the
	 * peer's own spelling survived interning.
	 */
	private static String decodedFieldName(HttpHeader header) throws Http3Exception {
		String name = header.toString();
		if (!hasUppercase(name)) {
			// Nothing was normalized away — these are the peer's octets, so the full check applies.
			validateFieldName(name);
			return name;
		}
		if (!isCanonicalizedByInterning(header)) {
			throw messageError("Uppercase octet in a field name");
		}
		return name.toLowerCase(Locale.ROOT);
	}

	private static boolean hasUppercase(String name) {
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c >= 'A' && c <= 'Z') return true;
		}
		return false;
	}

	/**
	 * Whether {@code header} is the token {@code core-http}'s registry holds for its name rather than one
	 * built out of received bytes — i.e. whether interning replaced the peer's spelling with the
	 * registry's own. {@link HttpHeaders#of(String)} returns the registered singleton for any
	 * case-insensitive match and a fresh instance for everything else, so reference identity <i>is</i>
	 * that question.
	 */
	private static boolean isCanonicalizedByInterning(HttpHeader header) {
		return HttpHeaders.of(header.toString()) == header;
	}

	/** The inverse of {@link #fromQpack}, for a field list on its way to a HEADERS frame. */
	public static List<QpackField> toQpack(List<Field> fields) {
		List<QpackField> result = new ArrayList<>(fields.size());
		for (Field field : fields) {
			result.add(new QpackField(HttpHeaders.of(field.name()), field.value().getBytes(StandardCharsets.ISO_8859_1)));
		}
		return result;
	}

	// endregion

	// region Content-Length reconciliation (FR-039)

	/**
	 * Reconciles a declared {@code Content-Length} against the actual total DATA payload received
	 * for the same message (RFC 9114 §4.2.2's smuggling defense — the HTTP/3 analogue of
	 * {@code core-http}'s strict RFC 7230 parsing, SI-7).
	 *
	 * @throws Http3Exception {@code H3_MESSAGE_ERROR} if the two disagree
	 */
	public static void checkContentLength(long declaredContentLength, long actualBodyBytes) throws Http3Exception {
		if (declaredContentLength != actualBodyBytes) {
			throw messageError("Content-Length " + declaredContentLength + " does not match " + actualBodyBytes + " received body byte(s)");
		}
	}

	// endregion
}
