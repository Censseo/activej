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

package io.activej.jsonrpc.transport.http.interop;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.ObjectConverter;
import com.dslplatform.json.runtime.Settings;
import io.activej.jsonrpc.ConformanceJson;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The loader of the frozen interoperability vectors (T049, T050 — FR-060…FR-065), in the format
 * defined by {@code data-model.md} §3: one JSON file, {@code http-vectors.json}, an array of
 * objects recording a captured real exchange — the request method, headers and body bytes as sent
 * by a real external client ({@code curl} or a standard JavaScript {@code fetch()}), and the
 * expected response status, an <b>allow-list</b> of asserted headers, and the expected body.
 * <p>
 * The format is deliberately independent of this module's Java types, exactly like feature 010's
 * {@code ConformanceVectors}: a maintainer regenerates it from a captured exchange without reading
 * any code (FR-064). The comparison rules are feature 010's own — {@link ConformanceJson} — so a
 * replay compares JSON <b>values</b>, never strings (FR-062, FR-065): key order and insignificant
 * whitespace are not part of the contract. Status and the asserted headers are compared exactly.
 * <p>
 * One {@link Vector} is one captured exchange; {@code origin} names the client it was captured
 * from. {@code bodyAbsent} is {@code true} for a bodiless response (the {@code 204} notification
 * answer and the {@code 405} rejection); when it is {@code true}, {@code expect.body} must be
 * absent, and when it is {@code false}, {@code expect.body} must be present.
 */
public final class InteropVectors {
	private InteropVectors() {}

	/** The single frozen file's resource path. */
	public static final String RESOURCE = "/io/activej/jsonrpc/transport/http/interop/http-vectors.json";

	private static final DslJson<Object> DSL_JSON =
		new DslJson<>(Settings.<Object>withRuntime().includeServiceLoader());

	/**
	 * One frozen exchange.
	 *
	 * @param name           unique, kebab-case; used in assertion messages and to name a failure
	 * @param origin         {@code curl} or {@code fetch} — which real client this exchange was captured from
	 * @param method         the request method, e.g. {@code POST}
	 * @param requestHeaders the request headers as sent — the stable ones; {@code Host} and
	 *                       {@code Content-Length} vary per run and are not recorded
	 * @param requestBody    the request body, verbatim; {@code null} for a bodiless request
	 * @param status         the expected HTTP status
	 * @param expectHeaders  only the headers this vector asserts — an allow-list, not the full set
	 * @param bodyAbsent     {@code true} when the response must carry no body (the {@code 204} and
	 *                       {@code 405} shapes); when {@code true}, {@code body} must be {@code null}
	 * @param body           the expected body, verbatim; compared by {@link ConformanceJson}'s JSON
	 *                       value equality, never by string equality
	 */
	public record Vector(
		String name, String origin, String method,
		Map<String, String> requestHeaders, @Nullable String requestBody,
		int status, Map<String, String> expectHeaders, boolean bodyAbsent, @Nullable String body
	) {
		@Override
		public String toString() {
			return name;
		}
	}

	/** Every frozen vector, in file order. */
	public static List<Vector> load() {
		return parse(read(RESOURCE));
	}

	/**
	 * The vectors of a document already in hand — for a consumer that reads the file itself, from a
	 * jar entry, a URL or anywhere else.
	 */
	public static List<Vector> parse(byte[] json) {
		Object parsed = readJson(json);
		if (!(parsed instanceof List<?> list)) {
			throw new IllegalStateException("http-vectors.json: expected a JSON array at the top level");
		}
		List<Vector> loaded = new ArrayList<>(list.size());
		for (Object element : list) {
			if (!(element instanceof Map<?, ?> vector)) {
				throw new IllegalStateException("http-vectors.json: every vector must be an object");
			}
			String name = required(vector, "name");
			String origin = required(vector, "origin");
			Object request = vector.get("request");
			if (!(request instanceof Map<?, ?> requestMap)) {
				throw new IllegalStateException("http-vectors.json: vector " + name + " has no request object");
			}
			Object expect = vector.get("expect");
			if (!(expect instanceof Map<?, ?> expectMap)) {
				throw new IllegalStateException("http-vectors.json: vector " + name + " has no expect object");
			}
			boolean bodyAbsent = Boolean.TRUE.equals(expectMap.get("bodyAbsent"));
			String body = asString(expectMap.get("body"), "expect.body");
			if (bodyAbsent && body != null) {
				throw new IllegalStateException("http-vectors.json: vector " + name +
					" sets expect.bodyAbsent=true and expect.body together; bodyAbsent forbids a body");
			}
			if (!bodyAbsent && body == null) {
				throw new IllegalStateException("http-vectors.json: vector " + name +
					" sets expect.bodyAbsent=false without expect.body");
			}
			loaded.add(new Vector(
				name, origin,
				required(requestMap, "method"),
				headers(requestMap.get("headers"), name + ".request.headers"),
				asString(requestMap.get("body"), "request.body"),
				intValue(expectMap.get("status"), name),
				headers(expectMap.get("headers"), name + ".expect.headers"),
				bodyAbsent,
				body));
		}
		return loaded;
	}

	private static Map<String, String> headers(@Nullable Object value, String label) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalStateException("http-vectors.json: " + label + " must be an object");
		}
		Map<String, String> headers = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String string)) {
				throw new IllegalStateException("http-vectors.json: " + label + " holds a non-string entry");
			}
			headers.put((String) entry.getKey(), string);
		}
		return headers;
	}

	private static int intValue(@Nullable Object value, String name) {
		if (!(value instanceof Number number)) {
			throw new IllegalStateException("http-vectors.json: vector " + name + " has a non-numeric expect.status");
		}
		return number.intValue();
	}

	private static String required(Map<?, ?> vector, String member) {
		String value = asString(vector.get(member), member);
		if (value == null) {
			throw new IllegalStateException("http-vectors.json: vector " + vector.get("name") +
				" has no " + member);
		}
		return value;
	}

	private static @Nullable String asString(@Nullable Object value, String member) {
		if (value == null) return null;
		if (!(value instanceof String string)) {
			throw new IllegalStateException("http-vectors.json: " + member + " must be a string, was " +
				value.getClass().getSimpleName());
		}
		return string;
	}

	private static byte[] read(String resource) {
		try (InputStream in = InteropVectors.class.getResourceAsStream(resource)) {
			if (in == null) throw new IllegalStateException("missing interop resource: " + resource);
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Object readJson(byte[] bytes) {
		try {
			JsonReader<Object> reader = DSL_JSON.newReader().process(bytes, bytes.length);
			reader.getNextToken();
			return ObjectConverter.deserializeObject(reader);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
