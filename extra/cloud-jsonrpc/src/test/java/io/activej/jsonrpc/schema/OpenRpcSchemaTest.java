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

package io.activej.jsonrpc.schema;

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.schema.fixtures.ReferenceApi;
import io.activej.jsonrpc.service.JsonRpcServiceContract;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The contract non-regression gate: the OpenRPC document {@link JsonRpcSchemaGenerator} emits for the
 * reference fixture must be, <b>byte for byte</b>, the document frozen at
 * {@value #REFERENCE_RESOURCE} (FR-050, FR-051; research Decision 7).
 *
 * <h2>Byte-exact, not member-wise</h2>
 * The encoder is deterministic — fixed member order (rule M10), methods sorted by wire name (rule M2) — which
 * is what licenses a byte comparison. Member-wise comparison was considered and rejected: it tolerates drift
 * nobody asked to tolerate. A change to {@link ReferenceApi}, to the {@code Type} → JSON Schema mapping, to
 * the document model or to its member order therefore surfaces <i>here</i>, as a deliberate diff on a
 * versioned file, and nowhere else silently.
 *
 * <h2>No network, no reactor, no I/O beyond one classpath read</h2>
 * CI never validates against anything remote (FR-051, SC-003): conformance to OpenRPC itself is established
 * once, offline, by a developer, and recorded in {@code VERDICT.md} beside the frozen file. This class reads
 * one classpath resource, calls one static generator and compares two arrays. There is no {@code Eventloop}
 * rule because the whole {@code io.activej.jsonrpc.schema} package is synchronous, and no {@code ByteBufRule}
 * because nothing here allocates a {@code ByteBuf}.
 *
 * <h2>A failure has to name the drift</h2>
 * The document is a single ~7 kB line of JSON; {@code assertArrayEquals}' "arrays first differed at element
 * [3187]" is not a review surface. {@link #describeDrift(byte[], byte[])} therefore reads both sides back
 * through {@link OpenRpcDocument#CODEC} and reports the drift in the vocabulary of the contract — methods
 * gained and lost, a parameter renamed, a schema changed, a {@code result} member appearing or disappearing
 * (which <i>is</i> the notification marker, rule M3) — and always ends with the first byte divergence and its
 * context, which is the one thing that still works when the frozen file is not a readable document at all.
 * That helper is itself tested below, because a diff nobody has ever seen produce a useful message is a
 * comment, not a tool.
 */
public final class OpenRpcSchemaTest {

	/**
	 * The frozen reference document: one file per fixture, named {@code <fixture-in-kebab-case>.openrpc.json}
	 * (see the directory's {@code README.md}).
	 */
	public static final String REFERENCE_RESOURCE = "/io/activej/jsonrpc/openrpc/reference-api.openrpc.json";

	/**
	 * The identity the frozen reference was generated with, emitted verbatim into its {@code info} member
	 * (FR-013 — never derived, never invented). Changing either value re-freezes the document, so it is
	 * spelled out here rather than borrowed from another test class.
	 */
	static final OpenRpcInfo INFO = new OpenRpcInfo("Reference API", "1.0.0");

	private static final String REGENERATION_HINT =
		"If the change is deliberate, re-freeze the reference with the shipped generator (never by hand) and\n" +
		"re-run the external validator, per src/test/resources/io/activej/jsonrpc/openrpc/README.md. If it is\n" +
		"not, the mapping or the fixture broke and the frozen bytes are the record of what it used to emit.";

	/** How many bytes of context to print on either side of the first divergence. */
	private static final int CONTEXT = 72;

	/** How much of a schema fragment to print before eliding the rest. */
	private static final int SCHEMA_ELISION = 240;

	// -------------------------------------------------------------------------------------------------
	// (a) The gate itself.
	// -------------------------------------------------------------------------------------------------

	@Test
	public void theFrozenReferenceIsExactlyWhatTheGeneratorEmitsForTheReferenceFixture() {
		byte[] frozen = readReference();
		byte[] generated = generateReference();

		if (!Arrays.equals(frozen, generated)) fail(describeDrift(frozen, generated));
	}

	@Test
	public void theFrozenReferenceReadsBackAsTheDocumentItClaimsToBe() throws MalformedDataException {
		// not implied by the comparison above - that one only says the two arrays agree. This says the frozen
		// bytes are a document THIS module can read, at the pinned OpenRPC version, describing the fixture's
		// wire names. A file that is byte-equal to a broken generator's output would pass (a) and fail here
		OpenRpcDocument frozen = JsonUtils.fromJsonBytes(OpenRpcDocument.CODEC, readReference());

		assertEquals(OpenRpcDocument.OPENRPC_VERSION, frozen.openrpc());
		assertEquals(INFO, frozen.info());
		assertEquals(
			JsonRpcServiceContract.of(ReferenceApi.class, JsonCodecFactory.defaultInstance()).wireNames(),
			new LinkedHashSet<>(names(frozen)));
	}

	@Test
	public void theFrozenReferenceIsCommittedAsTheGeneratorWroteIt() {
		byte[] frozen = readReference();

		// the generator emits no trailing newline and no BOM, so neither may be added by an editor on the way
		// into the repository: both would make the byte comparison fail for a reason that has nothing to do
		// with the contract. (.editorconfig covers *.java and *.xml/*.js/... - not *.json)
		assertEquals('{', frozen[0]);
		assertEquals('}', frozen[frozen.length - 1]);
	}

	// -------------------------------------------------------------------------------------------------
	// (b) The drift message. Every case below is a real contract change, expressed on the model and
	// re-encoded, so what is asserted is what a developer would actually read on the day it happens.
	// -------------------------------------------------------------------------------------------------

	@Test
	public void theDriftMessageNamesAMethodWhoseParamStructureChanged() {
		OpenRpcDocument reference = generateDocument();
		// rule M5 relaxed: the positional-only method becomes namable, and the member disappears
		byte[] drifted = encode(mapMethod(reference, "reference.sum",
			method -> new OpenRpcMethod(method.name(), method.params(), method.result(), null)));

		String message = describeDrift(encode(reference), drifted);

		assertTrue(message, message.contains("reference.sum"));
		assertTrue(message, message.contains("paramStructure"));
		assertTrue(message, message.contains(OpenRpcMethod.BY_POSITION));
		assertTrue(message, message.contains("first byte divergence at offset "));
	}

	@Test
	public void theDriftMessageNamesAMethodThatAppearedAndOneThatDisappeared() {
		OpenRpcDocument reference = generateDocument();
		List<OpenRpcMethod> methods = new ArrayList<>(reference.methods());
		OpenRpcMethod dropped = methods.remove(indexOf(methods, "reference.audit"));
		methods.add(new OpenRpcMethod("reference.audits", dropped.params(), dropped.result(), dropped.paramStructure()));

		String message = describeDrift(encode(reference),
			encode(new OpenRpcDocument(reference.openrpc(), reference.info(), methods)));

		assertTrue(message, message.contains("reference.audit:"));
		assertTrue(message, message.contains("reference.audits:"));
		assertTrue(message, message.contains("absent from the generated document"));
		assertTrue(message, message.contains("absent from the frozen reference"));
	}

	@Test
	public void theDriftMessageCallsOutAResultMemberThatAppearedOrDisappeared() {
		OpenRpcDocument reference = generateDocument();
		// rule M3: absence of `result` IS the notification marker, so this is the single most consequential
		// one-member change the document can suffer - a notification silently becoming callable, or the reverse
		OpenRpcContentDescriptor result = method(reference, "reference.archive").result();
		byte[] drifted = encode(mapMethod(reference, "reference.orderSeen",
			method -> new OpenRpcMethod(method.name(), method.params(), result, method.paramStructure())));

		String message = describeDrift(encode(reference), drifted);

		assertTrue(message, message.contains("reference.orderSeen"));
		assertTrue(message, message.contains("notification"));
		assertTrue(message, message.contains("result"));
	}

	@Test
	public void theDriftMessageNamesTheParameterWhoseSchemaChanged() {
		OpenRpcDocument reference = generateDocument();
		OpenRpcMethod scalars = method(reference, "reference.scalars");
		List<OpenRpcContentDescriptor> params = new ArrayList<>(scalars.params());
		// `count` widens from integer to number: the kind of mapping regression that no other test would see
		params.set(3, new OpenRpcContentDescriptor(params.get(3).name(), JsonSchemaFragment.Typed.of("number")));
		byte[] drifted = encode(mapMethod(reference, "reference.scalars",
			method -> new OpenRpcMethod(method.name(), params, method.result(), method.paramStructure())));

		String message = describeDrift(encode(reference), drifted);

		assertTrue(message, message.contains("reference.scalars"));
		assertTrue(message, message.contains("\"count\""));
		assertTrue(message, message.contains("{\"type\":\"integer\"}"));
		assertTrue(message, message.contains("{\"type\":\"number\"}"));
	}

	@Test
	public void theDriftMessageNamesTheMethodOrderWhenOnlyTheOrderChanged() {
		OpenRpcDocument reference = generateDocument();
		List<OpenRpcMethod> reordered = new ArrayList<>(reference.methods());
		reordered.add(reordered.remove(0)); // rule M2 broken: same methods, different order

		String message = describeDrift(encode(reference),
			encode(new OpenRpcDocument(reference.openrpc(), reference.info(), reordered)));

		assertTrue(message, message.contains("method order"));
		assertTrue(message, message.contains("reference.archive"));
	}

	@Test
	public void theDriftMessageNamesAChangedIdentityAndOpenRpcVersion() {
		OpenRpcDocument reference = generateDocument();
		byte[] drifted = encode(new OpenRpcDocument("1.5.0",
			new OpenRpcInfo("Reference API", "2.0.0"), reference.methods()));

		String message = describeDrift(encode(reference), drifted);

		assertTrue(message, message.contains("openrpc"));
		assertTrue(message, message.contains("1.5.0"));
		assertTrue(message, message.contains("info.version"));
		assertTrue(message, message.contains("2.0.0"));
	}

	@Test
	public void theDriftMessageFallsBackToTheByteWindowWhenTheFrozenBytesAreNotADocument() {
		// a truncated or hand-mangled reference file: the structural half cannot run, and the message must
		// still be useful rather than an exception thrown out of the failure path
		byte[] mangled = "{\"openrpc\":\"1.4.0\",\"info\":{\"title\":".getBytes(StandardCharsets.UTF_8);

		String message = describeDrift(mangled, generateReference());

		assertTrue(message, message.contains("not computable"));
		assertTrue(message, message.contains("frozen"));
		assertTrue(message, message.contains("offset "));
		assertTrue(message, message.contains(REFERENCE_RESOURCE));
	}

	@Test
	public void theDriftMessageReportsBothLengthsAndThePrefixCase() {
		byte[] reference = generateReference();
		byte[] truncated = Arrays.copyOf(reference, reference.length - 40);

		String message = describeDrift(reference, truncated);

		assertTrue(message, message.contains(reference.length + " bytes"));
		assertTrue(message, message.contains(truncated.length + " bytes"));
		assertTrue(message, message.contains("prefix"));
	}

	@Test
	public void identicalDocumentsAreNeverReported() {
		// the helper is only ever called on a real mismatch, but a describeDrift that invents drift would make
		// every future failure message untrustworthy
		byte[] reference = generateReference();

		assertArrayEquals(reference, generateReference());
		String message = describeDrift(reference, generateReference());
		assertTrue(message, message.contains("structural drift: none"));
		assertFalse(message, message.contains("->"));
	}

	// -------------------------------------------------------------------------------------------------
	// The drift message.
	// -------------------------------------------------------------------------------------------------

	/**
	 * Describes how {@code generated} differs from {@code frozen}, in the vocabulary of the OpenRPC contract
	 * first and in bytes second.
	 * <p>
	 * Package-private and total: it never throws, whatever the two arrays contain — it runs on the failure
	 * path, where a second exception would replace the diagnosis with its own stack trace.
	 */
	static String describeDrift(byte[] frozen, byte[] generated) {
		StringBuilder out = new StringBuilder();
		out.append("the generated OpenRPC document is not the frozen reference ").append(REFERENCE_RESOURCE)
			.append('\n')
			.append("  frozen:    ").append(frozen.length).append(" bytes\n")
			.append("  generated: ").append(generated.length).append(" bytes\n");
		appendStructuralDrift(out, frozen, generated);
		appendFirstDivergence(out, frozen, generated);
		return out.append('\n').append(REGENERATION_HINT).toString();
	}

	private static void appendStructuralDrift(StringBuilder out, byte[] frozen, byte[] generated) {
		out.append('\n');
		OpenRpcDocument frozenDocument = readBack(out, frozen, "frozen");
		OpenRpcDocument generatedDocument = readBack(out, generated, "generated");
		if (frozenDocument == null || generatedDocument == null) return;

		List<String> drift = drift(frozenDocument, generatedDocument);
		if (drift.isEmpty()) {
			out.append("structural drift: none -- the two read back EQUAL, so the difference is in the bytes\n")
				.append("alone: member order, spacing or escaping. That is a contract change too (rule M10).\n");
			return;
		}
		out.append("structural drift (frozen -> generated):\n");
		for (String line : drift) out.append("  ").append(line).append('\n');
	}

	private static @Nullable OpenRpcDocument readBack(StringBuilder out, byte[] document, String side) {
		try {
			return JsonUtils.fromJsonBytes(OpenRpcDocument.CODEC, document);
		} catch (MalformedDataException | RuntimeException e) {
			out.append("structural drift: not computable -- the ").append(side)
				.append(" bytes do not read back as an OpenRPC document (").append(e.getMessage()).append(")\n");
			return null;
		}
	}

	private static List<String> drift(OpenRpcDocument frozen, OpenRpcDocument generated) {
		List<String> drift = new ArrayList<>();
		if (!frozen.openrpc().equals(generated.openrpc())) {
			drift.add("openrpc: " + quote(frozen.openrpc()) + " -> " + quote(generated.openrpc()));
		}
		if (!frozen.info().title().equals(generated.info().title())) {
			drift.add("info.title: " + quote(frozen.info().title()) + " -> " + quote(generated.info().title()));
		}
		if (!frozen.info().version().equals(generated.info().version())) {
			drift.add("info.version: " + quote(frozen.info().version()) + " -> " + quote(generated.info().version()));
		}

		Map<String, OpenRpcMethod> frozenMethods = byName(frozen);
		Map<String, OpenRpcMethod> generatedMethods = byName(generated);

		for (String name : frozenMethods.keySet()) {
			if (!generatedMethods.containsKey(name)) {
				drift.add("- " + name + ": in the frozen reference, absent from the generated document");
			}
		}
		for (String name : generatedMethods.keySet()) {
			if (!frozenMethods.containsKey(name)) {
				drift.add("+ " + name + ": absent from the frozen reference, in the generated document");
			}
		}
		for (Map.Entry<String, OpenRpcMethod> entry : frozenMethods.entrySet()) {
			OpenRpcMethod counterpart = generatedMethods.get(entry.getKey());
			if (counterpart != null && !counterpart.equals(entry.getValue())) {
				driftOfMethod(drift, entry.getValue(), counterpart);
			}
		}

		// rule M2: same set, different order. Reported only once the per-method drift is out of the way, and
		// only over the names both documents have - an added or removed method shifts every later index
		Set<String> common = new LinkedHashSet<>(frozenMethods.keySet());
		common.retainAll(generatedMethods.keySet());
		List<String> frozenOrder = names(frozen).stream().filter(common::contains).toList();
		List<String> generatedOrder = names(generated).stream().filter(common::contains).toList();
		for (int i = 0; i < frozenOrder.size(); i++) {
			if (!frozenOrder.get(i).equals(generatedOrder.get(i))) {
				drift.add("~ method order at index " + i + ": " + quote(frozenOrder.get(i)) + " -> " +
						  quote(generatedOrder.get(i)) + " (methods are sorted by wire name, rule M2)");
				break;
			}
		}
		return drift;
	}

	private static void driftOfMethod(List<String> drift, OpenRpcMethod frozen, OpenRpcMethod generated) {
		String name = frozen.name();
		if (frozen.params().size() != generated.params().size()) {
			drift.add("~ " + name + ": params " + paramNames(frozen) + " -> " + paramNames(generated));
		} else {
			for (int i = 0; i < frozen.params().size(); i++) {
				OpenRpcContentDescriptor was = frozen.params().get(i);
				OpenRpcContentDescriptor is = generated.params().get(i);
				if (was.equals(is)) continue;
				if (!was.name().equals(is.name())) {
					drift.add("~ " + name + ": param[" + i + "] named " + quote(was.name()) + " -> " + quote(is.name()));
				}
				if (!was.schema().equals(is.schema())) {
					drift.add("~ " + name + ": param " + quote(was.name()) + " schema " + schema(was) + " -> " + schema(is));
				}
			}
		}

		if (frozen.result() == null && generated.result() != null) {
			drift.add("~ " + name + ": gained a result member -- it was a notification in the frozen reference " +
					  "and is a callable method now (rule M3)");
		} else if (frozen.result() != null && generated.result() == null) {
			drift.add("~ " + name + ": lost its result member -- it was a callable method in the frozen " +
					  "reference and is a notification now (rule M3)");
		} else if (frozen.result() != null && !frozen.result().equals(generated.result())) {
			if (!frozen.result().name().equals(generated.result().name())) {
				drift.add("~ " + name + ": result named " + quote(frozen.result().name()) + " -> " +
						  quote(generated.result().name()));
			}
			if (!frozen.result().schema().equals(generated.result().schema())) {
				drift.add("~ " + name + ": result schema " + schema(frozen.result()) + " -> " + schema(generated.result()));
			}
		}

		if (!Objects.equals(frozen.paramStructure(), generated.paramStructure())) {
			drift.add("~ " + name + ": paramStructure " + quoteOrAbsent(frozen.paramStructure()) + " -> " +
					  quoteOrAbsent(generated.paramStructure()));
		}
	}

	private static void appendFirstDivergence(StringBuilder out, byte[] frozen, byte[] generated) {
		int limit = Math.min(frozen.length, generated.length);
		int at = 0;
		while (at < limit && frozen[at] == generated[at]) at++;

		out.append('\n');
		if (at == limit) {
			out.append("the shorter document is a prefix of the longer one, ending at offset ").append(at)
				.append('\n');
		} else {
			out.append("first byte divergence at offset ").append(at).append(": frozen ").append(render(frozen[at]))
				.append(", generated ").append(render(generated[at])).append('\n');
		}

		int from = Math.max(0, at - CONTEXT);
		String prefix = (from == 0 ? "" : "...") + render(frozen, from, at);
		out.append("  frozen:    ").append(prefix).append(render(frozen, at, Math.min(frozen.length, at + CONTEXT)))
			.append('\n')
			.append("  generated: ").append(from == 0 ? "" : "...").append(render(generated, from, at))
			.append(render(generated, at, Math.min(generated.length, at + CONTEXT))).append('\n')
			// both lines carry the same 13-character label and the same identical prefix, so one caret column
			// serves both
			.append(" ".repeat(13)).append(" ".repeat(prefix.length())).append("^\n");
	}

	// -------------------------------------------------------------------------------------------------

	private static byte[] readReference() {
		try (InputStream in = OpenRpcSchemaTest.class.getResourceAsStream(REFERENCE_RESOURCE)) {
			if (in == null) {
				throw new AssertionError(
					"the frozen reference document " + REFERENCE_RESOURCE + " is not on the classpath. It is " +
					"generated from ReferenceApi by JsonRpcSchemaGenerator and committed -- see " +
					"src/test/resources/io/activej/jsonrpc/openrpc/README.md; it is never written by hand.");
			}
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static byte[] generateReference() {
		return JsonRpcSchemaGenerator.generateBytes(INFO,
			JsonRpcServiceContract.of(ReferenceApi.class, JsonCodecFactory.defaultInstance()));
	}

	private static OpenRpcDocument generateDocument() {
		return JsonRpcSchemaGenerator.generate(INFO,
			JsonRpcServiceContract.of(ReferenceApi.class, JsonCodecFactory.defaultInstance()));
	}

	private static byte[] encode(OpenRpcDocument document) {
		return JsonUtils.toJsonBytes(OpenRpcDocument.CODEC, document);
	}

	private static OpenRpcDocument mapMethod(
		OpenRpcDocument document, String wireName, Function<OpenRpcMethod, OpenRpcMethod> mapping
	) {
		List<OpenRpcMethod> methods = document.methods().stream()
			.map(method -> method.name().equals(wireName) ? mapping.apply(method) : method)
			.toList();
		return new OpenRpcDocument(document.openrpc(), document.info(), methods);
	}

	private static OpenRpcMethod method(OpenRpcDocument document, String wireName) {
		return document.methods().get(indexOf(document.methods(), wireName));
	}

	private static int indexOf(List<OpenRpcMethod> methods, String wireName) {
		for (int i = 0; i < methods.size(); i++) {
			if (methods.get(i).name().equals(wireName)) return i;
		}
		throw new AssertionError(wireName + " is absent from " + methods.stream().map(OpenRpcMethod::name).toList());
	}

	private static Map<String, OpenRpcMethod> byName(OpenRpcDocument document) {
		return document.methods().stream().collect(Collectors.toMap(
			OpenRpcMethod::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
	}

	private static List<String> names(OpenRpcDocument document) {
		return document.methods().stream().map(OpenRpcMethod::name).toList();
	}

	private static List<String> paramNames(OpenRpcMethod method) {
		return method.params().stream().map(OpenRpcContentDescriptor::name).toList();
	}

	private static String schema(OpenRpcContentDescriptor descriptor) {
		String json = JsonUtils.toJson(JsonSchemaMapping.SCHEMA_CODEC, descriptor.schema());
		return json.length() <= SCHEMA_ELISION ? json : json.substring(0, SCHEMA_ELISION) + "...(elided)";
	}

	private static String quote(String value) {
		return '"' + value + '"';
	}

	private static String quoteOrAbsent(@Nullable String value) {
		return value == null ? "(absent)" : quote(value);
	}

	private static String render(byte[] document, int from, int to) {
		StringBuilder out = new StringBuilder(to - from);
		for (int i = from; i < to; i++) out.append(render1(document[i]));
		return out.toString();
	}

	private static String render(byte value) {
		String hex = String.format("0x%02x", value & 0xff);
		return isPrintable(value) ? hex + " '" + (char) (value & 0xff) + '\'' : hex;
	}

	/** One column per byte, so the caret below the two windows lands on the divergence. */
	private static String render1(byte value) {
		return isPrintable(value) ? String.valueOf((char) (value & 0xff)) : ".";
	}

	private static boolean isPrintable(byte value) {
		int unsigned = value & 0xff;
		return unsigned >= 0x20 && unsigned < 0x7f;
	}
}
