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

import io.activej.jsonrpc.ConformanceVectors.Vector;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The vector loader itself (FR-062, Contract 3) — separately from the vectors it loads.
 */
public class ConformanceVectorsTest {

	// ---------------------------------------------------------------------------------------------------
	// THE distinction Contract 3 calls out: absent / null / the string "null" are three different things.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anExplicitJsonNullResponseMeansNoResponseDocumentAtAll() {
		Vector vector = one("""
			{"vectors":[{"name":"a","description":"d","request":"{}","response":null}]}""");
		assertTrue(vector.expectsNoResponse());
		assertNull(vector.response());
	}

	@Test
	public void anAbsentResponseMemberAlsoMeansNoResponseDocumentAtAll() {
		Vector vector = one("""
			{"vectors":[{"name":"a","description":"d","request":"{}"}]}""");
		assertTrue(vector.expectsNoResponse());
		assertNull(vector.response());
	}

	@Test
	public void theStringNullIsAResponseDocumentNotTheAbsenceOfOne() {
		// the four-character document `null`. This case does not occur among the shipped vectors, but the
		// loader's TYPE handling has to be right or the two collapse — which is the misreading Contract 3
		// exists to warn about
		Vector vector = one("""
			{"vectors":[{"name":"a","description":"d","request":"{}","response":"null"}]}""");
		assertFalse("a document whose body is `null` is still a document", vector.expectsNoResponse());
		assertEquals("null", vector.response());
		assertEquals(4, vector.response().length());
	}

	@Test
	public void theStringEmptyArrayIsAResponseDocumentToo() {
		Vector vector = one("""
			{"vectors":[{"name":"a","description":"d","request":"{}","response":"[]"}]}""");
		assertFalse(vector.expectsNoResponse());
		assertEquals("[]", vector.response());
	}

	@Test
	public void theThreeFormsAreMutuallyDistinguishableInOneFile() {
		List<Vector> vectors = ConformanceVectors.parse("""
			{"vectors":[\
			{"name":"absent","description":"d","request":"{}"},\
			{"name":"explicit-null","description":"d","request":"{}","response":null},\
			{"name":"literal-null-document","description":"d","request":"{}","response":"null"}]}\
			""".getBytes(UTF_8), "probe");

		assertEquals(3, vectors.size());
		assertTrue(vectors.get(0).expectsNoResponse());
		assertTrue(vectors.get(1).expectsNoResponse());
		assertFalse(vectors.get(2).expectsNoResponse());
	}

	// ---------------------------------------------------------------------------------------------------
	// Format handling.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void exactBytesDefaultsToFalseAndIsHonouredWhenSet() {
		assertFalse(one("""
			{"vectors":[{"name":"a","description":"d","request":"{}","response":"{}"}]}""").exactBytes());
		assertTrue(one("""
			{"vectors":[{"name":"a","description":"d","request":"{}","response":"{}","exactBytes":true}]}\
			""").exactBytes());
	}

	@Test
	public void aMalformedVectorFileIsRefusedLoudly() {
		assertThrows(IllegalStateException.class,
			() -> ConformanceVectors.parse("[]".getBytes(UTF_8), "probe"));
		assertThrows(IllegalStateException.class,
			() -> ConformanceVectors.parse("{}".getBytes(UTF_8), "probe"));
		assertThrows(IllegalStateException.class,
			() -> ConformanceVectors.parse("{\"vectors\":[1]}".getBytes(UTF_8), "probe"));
		// a vector missing a required member
		assertThrows(IllegalStateException.class, () -> ConformanceVectors.parse(
			"{\"vectors\":[{\"name\":\"a\",\"request\":\"{}\"}]}".getBytes(UTF_8), "probe"));
		// a response that is not a string and not null
		assertThrows(IllegalStateException.class, () -> ConformanceVectors.parse(
			"{\"vectors\":[{\"name\":\"a\",\"description\":\"d\",\"request\":\"{}\",\"response\":42}]}"
				.getBytes(UTF_8), "probe"));
	}

	@Test
	public void aMissingResourceIsRefusedLoudly() {
		assertThrows(IllegalStateException.class, () -> ConformanceVectors.load("no-such-file.json"));
	}

	// ---------------------------------------------------------------------------------------------------
	// The shipped files.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void bothShippedFilesLoad() {
		assertEquals(15, ConformanceVectors.load(ConformanceVectors.SPEC_EXAMPLES).size());
		assertEquals(15, ConformanceVectors.load(ConformanceVectors.HARDENING).size());
		assertEquals(30, ConformanceVectors.loadAll().size());
	}

	@Test
	public void aVectorIsReachableByItsStableName() {
		Vector vector = ConformanceVectors.byName("batch-all-notifications");
		assertEquals("batch-all-notifications", vector.name());
		assertTrue(vector.expectsNoResponse());
		assertTrue(vector.request().startsWith("["));

		assertThrows(IllegalArgumentException.class, () -> ConformanceVectors.byName("no-such-vector"));
	}

	@Test
	public void theBomVectorReallyCarriesAByteOrderMark() {
		// this one is invisible in the file, so assert the bytes rather than trusting the eye
		byte[] request = ConformanceVectors.byName("leading-bom").request().getBytes(UTF_8);
		assertEquals((byte) 0xEF, request[0]);
		assertEquals((byte) 0xBB, request[1]);
		assertEquals((byte) 0xBF, request[2]);
		assertEquals('{', request[3]);
	}

	@Test
	public void theBoundVectorsReallyExceedTheirBounds() {
		assertTrue(ConformanceVectors.byName("envelope-too-large").request().getBytes(UTF_8).length >
				   JsonRpcLimits.MAX_BODY_SIZE.toLong());
		assertEquals(JsonRpcLimits.MAX_BATCH_SIZE + 1,
			ConformanceVectors.byName("batch-too-large").request().split("\\{\"jsonrpc\"", -1).length - 1);
		assertTrue(ConformanceVectors.byName("nesting-too-deep").request().chars().filter(c -> c == '[').count() >
				   JsonRpcLimits.MAX_JSON_DEPTH - 1);
	}

	/**
	 * FR-062 — the loader must stay ignorable of this feature's model, so a downstream consumer can use it
	 * knowing only the file format.
	 * <p>
	 * A source scan rather than an import scan: the loader sits in {@code io.activej.jsonrpc} (the path the
	 * plan specifies), so it could name a type of this feature with <b>no import at all</b>. Every such type
	 * is prefixed {@code JsonRpc}, which is what this looks for.
	 */
	@Test
	public void theLoaderReferencesNoTypeOfThisFeature() throws IOException {
		Path source = Path.of("src", "test", "java", "io", "activej", "jsonrpc", "ConformanceVectors.java");
		if (!Files.isRegularFile(source)) {
			source = Path.of("extra", "cloud-jsonrpc", "src", "test", "java", "io", "activej", "jsonrpc",
				"ConformanceVectors.java");
		}
		String text = Files.readString(source, UTF_8);
		assertFalse("the vector loader must not name any JsonRpc* type — the vectors are data, not an " +
					"extension of this feature's test suite (FR-062)", text.contains("JsonRpc"));
	}

	private static Vector one(String json) {
		List<Vector> vectors = ConformanceVectors.parse(json.getBytes(UTF_8), "probe");
		assertEquals(1, vectors.size());
		return vectors.get(0);
	}
}
