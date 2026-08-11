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

import io.activej.common.MemSize;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User Story 4 — the three bounds ship enabled, are overridable, and each refuses <b>before</b> paying the
 * cost it exists to prevent (FR-050…FR-054).
 */
public class JsonRpcLimitsTest {

	// ---------------------------------------------------------------------------------------------------
	// T052 — the three defaults, their keys, and their error codes.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theThreeDefaults() {
		assertEquals(MemSize.megabytes(1).toLong(), JsonRpcLimits.MAX_BODY_SIZE.toLong());
		assertEquals(100, JsonRpcLimits.MAX_BATCH_SIZE);
		assertEquals(64, JsonRpcLimits.MAX_JSON_DEPTH);
	}

	@Test
	public void allThreeAreReadableWithoutAnInstance() {
		// FR-053: a transport must be able to consult MAX_BODY_SIZE *during* accumulation, before an envelope
		// array exists — so the values cannot hang off a component this feature deliberately does not have
		for (String field : new String[]{"MAX_BODY_SIZE", "MAX_BATCH_SIZE", "MAX_JSON_DEPTH"}) {
			int modifiers;
			try {
				modifiers = JsonRpcLimits.class.getField(field).getModifiers();
			} catch (NoSuchFieldException e) {
				throw new AssertionError(field + " must be a public field", e);
			}
			assertTrue(field + " must be static", Modifier.isStatic(modifiers));
			assertTrue(field + " must be final — resolved once, never mutated (DI-5)", Modifier.isFinal(modifiers));
			assertTrue(field + " must be public", Modifier.isPublic(modifiers));
		}
		assertEquals("JsonRpcLimits must not be instantiable", 0, JsonRpcLimits.class.getConstructors().length);
		assertTrue(Modifier.isFinal(JsonRpcLimits.class.getModifiers()));
	}

	@Test
	public void eachBoundIsOverridableByItsSimpleNameKey() throws Exception {
		assertOverride("JsonRpcLimits.maxBodySize", "4mb", "MAX_BODY_SIZE", MemSize.megabytes(4).toLong());
		assertOverride("JsonRpcLimits.maxBatchSize", "7", "MAX_BATCH_SIZE", 7L);
		assertOverride("JsonRpcLimits.maxJsonDepth", "9", "MAX_JSON_DEPTH", 9L);
	}

	@Test
	public void eachBoundIsOverridableByItsFullyQualifiedKey() throws Exception {
		assertOverride("io.activej.jsonrpc.JsonRpcLimits.maxBodySize", "512kb", "MAX_BODY_SIZE",
			MemSize.kilobytes(512).toLong());
		assertOverride("io.activej.jsonrpc.JsonRpcLimits.maxBatchSize", "3", "MAX_BATCH_SIZE", 3L);
		assertOverride("io.activej.jsonrpc.JsonRpcLimits.maxJsonDepth", "5", "MAX_JSON_DEPTH", 5L);
	}

	@Test
	public void anEnvelopeLongerThanMaxBodySizeIsRefused() {
		int max = JsonRpcLimits.MAX_BODY_SIZE.toInt();

		byte[] tooLong = paddedEnvelope(max + 1);
		assertEquals(max + 1, tooLong.length);
		assertRefused(tooLong, JsonRpcErrors.REQUEST_TOO_LARGE);

		// exactly at the bound is accepted, or the refusal above would prove nothing
		byte[] exactlyAtTheBound = paddedEnvelope(max);
		assertEquals(max, exactlyAtTheBound.length);
		assertTrue("a document exactly at the bound must decode",
			JsonRpcDecoder.decode(exactlyAtTheBound) instanceof JsonRpcRequest);
	}

	@Test
	public void theSizeBoundIsMeasuredOnTheDecodedRegionNotTheWholeArray() {
		// decode(array, offset, length) bounds `length`, so a transport handing over a slice of a bigger
		// buffer is judged on what it actually asked to decode
		int max = JsonRpcLimits.MAX_BODY_SIZE.toInt();
		byte[] small = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"}".getBytes(UTF_8);
		byte[] framed = new byte[max + 100];
		System.arraycopy(small, 0, framed, 10, small.length);

		assertTrue(JsonRpcDecoder.decode(framed, 10, small.length) instanceof JsonRpcRequest);
	}

	@Test
	public void aBatchWithMoreElementsThanMaxBatchSizeIsRefused() {
		int max = JsonRpcLimits.MAX_BATCH_SIZE;

		// one document for the whole batch, not one error per excess element (FR-054)
		assertRefused(batchOf(max + 1), JsonRpcErrors.BATCH_TOO_LARGE);
		assertRefused(batchOf(max + 50), JsonRpcErrors.BATCH_TOO_LARGE);

		JsonRpcInput atTheBound = JsonRpcDecoder.decode(batchOf(max));
		assertTrue("a batch exactly at the bound must decode", atTheBound instanceof JsonRpcBatch);
		assertEquals(max, ((JsonRpcBatch) atTheBound).size());
	}

	@Test
	public void anEnvelopeNestedDeeperThanMaxJsonDepthIsRefused() {
		int max = JsonRpcLimits.MAX_JSON_DEPTH;

		assertRefused(nestedParams(max + 1), JsonRpcErrors.NESTING_TOO_DEEP);
		assertRefused(nestedParams(max * 10), JsonRpcErrors.NESTING_TOO_DEEP);

		assertTrue("a document exactly at the bound must decode",
			JsonRpcDecoder.decode(nestedParams(max)) instanceof JsonRpcRequest);
	}

	@Test
	public void allThreeBoundsShipEnabled() {
		// FR-051: a consumer opts OUT by raising a bound, never IN by enabling one. There is no "off" value
		// and no disable switch anywhere.
		assertTrue(JsonRpcLimits.MAX_BODY_SIZE.toLong() > 0);
		assertTrue(JsonRpcLimits.MAX_BATCH_SIZE > 0);
		assertTrue(JsonRpcLimits.MAX_JSON_DEPTH > 0);
	}

	// ---------------------------------------------------------------------------------------------------
	// T053 — ordering. A bound that fires only after the work it was meant to avoid is not a bound.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theDepthRefusalHappensBeforeAnyParsing() {
		// each of these would produce a DIFFERENT error if it ever reached the parser; -32003 winning is the
		// proof that the scan ran first
		int deep = JsonRpcLimits.MAX_JSON_DEPTH + 10;

		// unparseable: nothing but opening brackets. Parsing gives -32700
		assertRefused(("[".repeat(deep)).getBytes(UTF_8), JsonRpcErrors.NESTING_TOO_DEEP);

		// parseable but invalid: a bad version member. Classification gives -32600
		assertRefused(("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"m\",\"params\":" +
					   "[".repeat(deep) + "1" + "]".repeat(deep) + "}").getBytes(UTF_8),
			JsonRpcErrors.NESTING_TOO_DEEP);

		// parseable but malformed UTF-8: the UTF-8 scan gives -32700
		byte[] withBadUtf8 = concat(
			("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":" + "[".repeat(deep)).getBytes(UTF_8),
			new byte[]{(byte) 0xC0, (byte) 0xAF},
			("]".repeat(deep) + "}").getBytes(UTF_8));
		assertRefused(withBadUtf8, JsonRpcErrors.NESTING_TOO_DEEP);
	}

	@Test
	public void theSizeRefusalHappensBeforeTheDepthScan() {
		// the cheapest check runs first: an over-size AND over-deep document is refused on size
		int deep = JsonRpcLimits.MAX_JSON_DEPTH + 10;
		String padding = "x".repeat(JsonRpcLimits.MAX_BODY_SIZE.toInt());
		byte[] both = ("[\"" + padding + "\"," + "[".repeat(deep) + "1" + "]".repeat(deep) + "]").getBytes(UTF_8);

		assertTrue(both.length > JsonRpcLimits.MAX_BODY_SIZE.toInt());
		assertRefused(both, JsonRpcErrors.REQUEST_TOO_LARGE);
	}

	@Test
	public void theBatchRefusalHappensOnTheElementThatExceedsTheBound() {
		// FR-054: the batch is refused on the element that would exceed the bound, BEFORE that element and
		// every element after it is decoded or retained. Element 101 here is unparseable, so a decoder that
		// built the whole list first would report -32700; -32002 is the proof it stopped in time.
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < JsonRpcLimits.MAX_BATCH_SIZE; i++) {
			json.append("{\"jsonrpc\":\"2.0\",\"method\":\"n\"},");
		}
		json.append("{\"jsonrpc\":");        // truncated — would throw if it were ever parsed
		json.append(']');

		assertRefused(json.toString().getBytes(UTF_8), JsonRpcErrors.BATCH_TOO_LARGE);
	}

	@Test
	public void theBatchRefusalDoesNotDependOnTheExcessElementsBeingValid() {
		// the same document with a well-formed element 101 must give the same answer, or the test above would
		// be passing for the wrong reason
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < JsonRpcLimits.MAX_BATCH_SIZE; i++) {
			json.append("{\"jsonrpc\":\"2.0\",\"method\":\"n\"},");
		}
		json.append("{\"jsonrpc\":\"2.0\",\"method\":\"n\"}]");

		assertRefused(json.toString().getBytes(UTF_8), JsonRpcErrors.BATCH_TOO_LARGE);
	}

	@Test
	public void aRefusedBoundCarriesNoIdAndNoData() {
		// the refusal happens before any member is read, so there is nothing to recover — and an emitted
		// error never carries data (FR-089)
		for (byte[] envelope : new byte[][]{
			paddedEnvelope(JsonRpcLimits.MAX_BODY_SIZE.toInt() + 1),
			batchOf(JsonRpcLimits.MAX_BATCH_SIZE + 1),
			nestedParams(JsonRpcLimits.MAX_JSON_DEPTH + 1)}) {
			JsonRpcMalformed malformed = (JsonRpcMalformed) JsonRpcDecoder.decode(envelope);
			assertEquals(JsonRpcId.NULL, malformed.id());
			assertTrue(malformed.error().data().isAbsent());
		}
	}

	// ---------------------------------------------------------------------------------------------------

	private static void assertRefused(byte[] envelope, JsonRpcError expected) {
		JsonRpcInput input = JsonRpcDecoder.decode(envelope);
		if (!(input instanceof JsonRpcMalformed malformed)) {
			throw new AssertionError("expected " + expected.message() + ", but it decoded to " + input);
		}
		assertSame("expected " + expected.message() + ", got " + malformed.error(), expected, malformed.error());
	}

	/** A well-formed request padded with a string parameter so the whole document is exactly {@code size} bytes. */
	private static byte[] paddedEnvelope(int size) {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":[\"";
		String suffix = "\"]}";
		int padding = size - prefix.length() - suffix.length();
		if (padding < 0) throw new IllegalArgumentException("size too small: " + size);
		return (prefix + "x".repeat(padding) + suffix).getBytes(UTF_8);
	}

	/** A batch of {@code count} well-formed notifications. */
	private static byte[] batchOf(int count) {
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) json.append(',');
			json.append("{\"jsonrpc\":\"2.0\",\"method\":\"n\"}");
		}
		return json.append(']').toString().getBytes(UTF_8);
	}

	/** A well-formed request whose {@code params} nest to exactly {@code depth} total document levels. */
	private static byte[] nestedParams(int depth) {
		int arrays = depth - 1;                 // the envelope object itself is level 1
		return ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":" +
				"[".repeat(arrays) + "]".repeat(arrays) + "}").getBytes(UTF_8);
	}

	private static byte[] concat(byte[]... parts) {
		int total = 0;
		for (byte[] part : parts) total += part.length;
		byte[] joined = new byte[total];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, joined, offset, part.length);
			offset += part.length;
		}
		return joined;
	}

	/**
	 * Reloads {@link JsonRpcLimits} in a child loader with {@code key} set, so the {@code static final} is
	 * resolved again. Without this the settings keys are asserted by nobody and a typo in one is invisible.
	 */
	private static void assertOverride(String key, String value, String field, long expected) throws Exception {
		System.setProperty(key, value);
		try {
			Class<?> reloaded = Class.forName("io.activej.jsonrpc.JsonRpcLimits", true,
				new ModuleReloadingClassLoader(JsonRpcLimitsTest.class.getClassLoader()));
			assertFalse("the class must have been re-initialised", reloaded == JsonRpcLimits.class);

			Object resolved = reloaded.getField(field).get(null);
			long actual = resolved instanceof Integer integer ? integer : ((MemSize) resolved).toLong();
			assertEquals(key + '=' + value, expected, actual);
		} finally {
			System.clearProperty(key);
		}
	}

	/** Defines {@code io.activej.jsonrpc.*} itself so their static initialisers run again. */
	private static final class ModuleReloadingClassLoader extends ClassLoader {
		private ModuleReloadingClassLoader(ClassLoader parent) {
			super(parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.startsWith("io.activej.jsonrpc.") || name.endsWith("Test")) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					String resource = name.replace('.', '/') + ".class";
					try (InputStream in = getParent().getResourceAsStream(resource)) {
						if (in == null) throw new ClassNotFoundException(name);
						byte[] bytes = in.readAllBytes();
						loaded = defineClass(name, bytes, 0, bytes.length);
					} catch (IOException e) {
						throw new ClassNotFoundException(name, e);
					}
				}
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}
	}

	static {
		// a guard against a future default change silently invalidating every fixture above
		if (JsonRpcLimits.MAX_BATCH_SIZE < 2 || JsonRpcLimits.MAX_JSON_DEPTH < 4) {
			fail("the fixtures in this class assume workable defaults");
		}
	}
}
