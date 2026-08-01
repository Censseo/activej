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

	private static final Set<String> REQUEST_PSEUDO_HEADERS = Set.of(METHOD, SCHEME, AUTHORITY, PATH);
	private static final Set<String> RESPONSE_PSEUDO_HEADERS = Set.of(STATUS);

	// RFC 9114 §4.2 — fields whose semantics are specific to a single hop-by-hop HTTP/1.1/2
	// connection and therefore have no meaning in HTTP/3, which has none.
	private static final Set<String> CONNECTION_SPECIFIC_FIELDS =
		Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade");

	private static final String TE_FIELD = "te";
	private static final String TE_ALLOWED_VALUE = "trailers";

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
	 *
	 * @throws Http3Exception {@code H3_MESSAGE_ERROR} on any validation failure, a missing
	 *                        required pseudo-header, an unknown {@code :method}, or a
	 *                        {@code :scheme}/{@code :authority}/{@code :path} combination that
	 *                        does not form a valid request target
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

		String authority = pseudo.get(AUTHORITY);
		if (authority == null) {
			for (Field field : regular) {
				if (field.name().equals("host")) {
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
			if (field.name().equals("host")) continue;
			builder.withHeader(HttpHeaders.of(field.name()), field.value());
		}
		return builder;
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
			throw messageError("Non-numeric :status value: " + statusValue);
		}
		if (code < 100 || code >= 600) {
			throw messageError("Out-of-range :status value: " + statusValue);
		}

		HttpResponse.Builder builder = HttpMessages.responseBuilder(HttpVersion.HTTP_3_0, code);
		for (Field field : regular) {
			builder.withHeader(HttpHeaders.of(field.name()), field.value());
		}
		return builder;
	}

	private static Map<String, String> collectAndValidate(List<Field> fields, Mode mode, List<Field> regularOut) throws Http3Exception {
		Map<String, String> pseudo = new LinkedHashMap<>();
		boolean seenRegular = false;
		for (Field field : fields) {
			String name = field.name();
			validateFieldName(name);
			if (!name.isEmpty() && name.charAt(0) == ':') {
				if (mode == Mode.TRAILERS) {
					throw messageError("Pseudo-header in a trailer section: " + name);
				}
				if (seenRegular) {
					throw messageError("Pseudo-header after a regular field: " + name);
				}
				Set<String> allowed = mode == Mode.REQUEST ? REQUEST_PSEUDO_HEADERS : RESPONSE_PSEUDO_HEADERS;
				if (!allowed.contains(name)) {
					throw messageError("Unknown pseudo-header: " + name);
				}
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
				regularOut.add(field);
			}
		}
		return pseudo;
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
				throw messageError("Uppercase octet in a field name: " + name);
			}
			if (!isLowerTchar(c)) {
				throw messageError("Illegal octet in a field name: " + name);
			}
		}
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
