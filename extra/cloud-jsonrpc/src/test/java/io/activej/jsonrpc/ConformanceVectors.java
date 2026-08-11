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

package io.activej.jsonrpc;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.ObjectConverter;
import com.dslplatform.json.runtime.Settings;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads the JSON-RPC conformance vectors from
 * {@code src/test/resources/io/activej/jsonrpc/conformance/*.json}.
 *
 * <h2>It knows nothing about this feature, on purpose (FR-062)</h2>
 * This class references <b>no type of {@code io.activej.jsonrpc}</b> — no decoder, no message, no error.
 * It reads a small, trusted, local file into {@link Vector} records of plain {@code String}s. That is the
 * whole point: features 03, 04, 06, 07 and 08 must be able to replay these vectors against a live transport
 * knowing only the <i>file format</i>, without compiling against this module's model. If this class ever
 * needs an import from {@code io.activej.jsonrpc}, the vectors have stopped being data and become an
 * extension of this feature's test suite.
 *
 * <h2>Vector names are stable once published (FR-062, Contract 3)</h2>
 * A downstream feature may reference a vector <b>by name</b>. Renaming or removing one breaks that
 * feature's test; <b>adding</b> vectors is always safe. Both files repeat this in a {@code "stability"}
 * field so the rule travels with the data.
 *
 * <h2>The one place this format can be misread</h2>
 * {@code "response": null} — and an absent {@code response} member — mean <b>"no response document at
 * all"</b>: the peer sends nothing, zero bytes. That is <i>not</i> the same as {@code "response": "null"},
 * whose value is the four-character document {@code null}, nor as {@code "response": "[]"}. Contract 3
 * calls this out because it encodes the rule (§4.1, §6) most often implemented wrongly, so
 * {@link Vector#expectsNoResponse()} is the distinction made explicit rather than left to a null check at
 * each call site.
 */
public final class ConformanceVectors {
	private ConformanceVectors() {}

	/** The 15 normative examples of §7 of the JSON-RPC 2.0 specification. */
	public static final String SPEC_EXAMPLES = "spec-examples.json";

	/** The 15 vectors for this implementation's own codes and strictness decisions. */
	public static final String HARDENING = "hardening.json";

	private static final String DIRECTORY = "/io/activej/jsonrpc/conformance/";

	private static final DslJson<Object> DSL_JSON =
		new DslJson<>(Settings.<Object>withRuntime().includeServiceLoader());

	/**
	 * One conformance vector.
	 *
	 * @param name        stable identifier, kebab-case, unique across all files
	 * @param description the property this vector pins; quoted verbatim by failure messages
	 * @param request     the input document, verbatim — including deliberately malformed JSON
	 * @param response    the expected response document, or {@code null} for <b>no response at all</b>
	 * @param exactBytes  compare raw bytes rather than JSON values
	 */
	public record Vector(
		String name, String description, String request, @Nullable String response, boolean exactBytes
	) {
		/**
		 * Whether this vector expects <b>no response document at all</b> — zero bytes on the wire.
		 * <p>
		 * Distinct from a vector whose {@code response} is the string {@code "null"} (a document whose body
		 * is the JSON literal {@code null}) and from one whose response is {@code "[]"}.
		 */
		public boolean expectsNoResponse() {
			return response == null;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/** Every vector of both files, in file order. */
	public static List<Vector> loadAll() {
		List<Vector> all = new ArrayList<>(load(SPEC_EXAMPLES));
		all.addAll(load(HARDENING));
		return all;
	}

	/** The vectors of one file. */
	public static List<Vector> load(String fileName) {
		return parse(read(DIRECTORY + fileName), fileName);
	}

	/**
	 * The vectors of a document already in hand — for a consumer that reads the file itself, from a jar
	 * entry, a URL or anywhere else.
	 *
	 * @param label how to name this document in a failure message
	 */
	public static List<Vector> parse(byte[] json, String label) {
		Object parsed = readJson(json);
		if (!(parsed instanceof Map<?, ?> document)) {
			throw new IllegalStateException(label + ": expected a JSON object at the top level");
		}
		Object vectors = document.get("vectors");
		if (!(vectors instanceof List<?> list)) {
			throw new IllegalStateException(label + ": expected a \"vectors\" array");
		}

		List<Vector> loaded = new ArrayList<>(list.size());
		for (Object element : list) {
			if (!(element instanceof Map<?, ?> vector)) {
				throw new IllegalStateException(label + ": every vector must be an object");
			}
			loaded.add(new Vector(
				required(label, vector, "name"),
				required(label, vector, "description"),
				required(label, vector, "request"),
				// THE distinction: an absent member and an explicit JSON null both mean "no response at
				// all". A member whose value is the STRING "null" is a response document, and reaches the
				// cast below like any other string.
				asString(label, vector.get("response"), "response"),
				Boolean.TRUE.equals(vector.get("exactBytes"))));
		}
		return loaded;
	}

	/** Looks a vector up by its stable name across both files. */
	public static Vector byName(String name) {
		for (Vector vector : loadAll()) {
			if (vector.name().equals(name)) return vector;
		}
		throw new IllegalArgumentException("no such conformance vector: " + name);
	}

	private static String required(String fileName, Map<?, ?> vector, String member) {
		String value = asString(fileName, vector.get(member), member);
		if (value == null) {
			throw new IllegalStateException(fileName + ": vector " + vector.get("name") + " has no " + member);
		}
		return value;
	}

	private static @Nullable String asString(String fileName, @Nullable Object value, String member) {
		if (value == null) return null;
		if (!(value instanceof String string)) {
			throw new IllegalStateException(fileName + ": " + member + " must be a string, was " +
										   value.getClass().getSimpleName());
		}
		return string;
	}

	private static byte[] read(String resource) {
		try (InputStream in = ConformanceVectors.class.getResourceAsStream(resource)) {
			if (in == null) throw new IllegalStateException("missing conformance resource: " + resource);
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
