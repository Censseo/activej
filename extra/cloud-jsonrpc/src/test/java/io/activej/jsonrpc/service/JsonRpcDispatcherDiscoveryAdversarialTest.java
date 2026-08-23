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

package io.activej.jsonrpc.service;

import io.activej.jsonrpc.JsonRpcBatch;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.JsonRpcMalformed;
import io.activej.jsonrpc.JsonRpcMessage;
import io.activej.jsonrpc.JsonRpcOutput;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.schema.OpenRpcInfo;
import io.activej.jsonrpc.schema.fixtures.ReferenceApi;
import io.activej.jsonrpc.schema.fixtures.ReferenceApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adversarial suite for the <b>discovery</b> surface at the envelope layer (feature 018) — hostile documents
 * aimed specifically at {@code rpc.discover}, at the pre-computed OpenRPC document, and at the interaction
 * between a <b>multi-kilobyte, growing</b> answer and framing/size mechanics that were tuned when every
 * response was a few dozen bytes.
 *
 * <h2>What this class deliberately does not re-test</h2>
 * The generic JSON-RPC line is already adversarially covered and is not re-litigated here: the 30 conformance
 * vectors, {@link JsonRpcDispatcherAdversarialTest} (hostile implementations and hostile inspectors),
 * {@code JsonRpcJmxAdversarialTest} (cardinality and disclosure), and the transports' own
 * {@code *Adversarial*}/{@code *Hostile*} suites. The functional contract of discovery is
 * {@link JsonRpcDispatcherDiscoveryTest}; every assertion below is either a shape that file does not exercise
 * or a strictly sharper form of one.
 *
 * <h2>The oracle</h2>
 * Wherever "the same as any other unregistered name" or "the same as any other garbled document" is claimed,
 * it is asserted as a <b>byte comparison against that other document's actual answer</b>, computed in the same
 * test — never as "both are {@code -32601}". A near-miss spelling that produced a differently-worded, differently
 * ordered or differently-sized {@code -32601} would still be an oracle for a prober, and a code-level assertion
 * would not see it.
 *
 * <h2>Two characterization tests, not bug hunts</h2>
 * <ul>
 *     <li>{@link #theServedArrayIsTheVeryArrayDiscoveryDocumentHandsOut()} pins the documented
 *     <b>no-defensive-copy</b> design of {@link JsonRpcDispatcher#discoveryDocument()} — including the
 *     consequence, by mutating a byte and observing the next answer change. If a future change starts copying,
 *     this fails as a <i>behaviour change</i> rather than being accepted silently as "more correct".</li>
 *     <li>{@link #aDocumentLargerThanMaxBodySizeIsServedAndRefusedByAConformingPeer()} pins the one place this
 *     stack systematically emits a response bigger than its own inbound bound. It is not a defect of the
 *     dispatcher — nothing bounds an <i>outgoing</i> document anywhere in the line — but it is the discovery
 *     answer, and only the discovery answer, that grows with the service and can cross
 *     {@link JsonRpcLimits#MAX_BODY_SIZE} without anybody choosing to make it.</li>
 * </ul>
 */
public class JsonRpcDispatcherDiscoveryAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String RPC_DISCOVER = "rpc.discover";

	/** An arbitrary name that is certainly not registered — the oracle every near-miss is compared against. */
	private static final String UNREGISTERED = "no.such.method";

	private static final OpenRpcInfo INFO = new OpenRpcInfo("Reference API", "1.2.3");

	private JsonRpcDispatcher plain;
	private JsonRpcDispatcher discovering;
	/** The very array {@link #discovering} answers with — not a copy, by design. */
	private byte[] document;
	/** {@link #document} as text, for substring assertions only. */
	private String documentText;

	@Before
	public void setUp() {
		plain = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.build();
		discovering = discovering(INFO);
		document = discovering.discoveryDocument();
		assertNotNull(document);
		documentText = new String(document, UTF_8);
	}

	private static JsonRpcDispatcher discovering(OpenRpcInfo info) {
		return JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.withDiscovery(info)
			.build();
	}

	// ---------------------------------------------------------------------------------------------------
	// 1 — rpc.discover as one element of a batch of deliberately broken siblings.
	//
	// The attack: a peer that cannot reach the document with a clean call tries to reach it wrapped in
	// garbage, hoping either that one broken sibling poisons the whole batch, or that the batch machinery
	// takes a different (less validated) path to the built-in entry. Discovery is a table entry, so per-element
	// isolation must hold exactly as it does for a user method — including the element ORDER, which is the
	// only thing correlating an answer with its request when several elements share the id null.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aDiscoverElementIsIsolatedFromEveryBrokenSiblingInItsBatch() {
		String batch = "[" +
					   req("1", RPC_DISCOVER, null) + ',' +                          // the one that must work
					   "\"not an object\"," +                                        // -32600, id null
					   req("3", UNREGISTERED, null) + ',' +                          // -32601
					   req("4", "reference.sum", "{\"a\":1}") + ',' +                // named params to positional
					   "{\"jsonrpc\":\"1.0\",\"id\":5,\"method\":\"rpc.discover\"}," +  // wrong version
					   req("6", RPC_DISCOVER, "[1]") + ',' +                         // -32602
					   "[1,2]," +                                                    // a nested array element
					   "{\"jsonrpc\":\"2.0\",\"method\":\"rpc.discover\"}," +           // notification: no element
					   req("9", "reference.archive", "[7]") +                        // an ordinary success
					   ']';

		assertEquals("[" +
					 "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + documentText + "}," +
					 error(null, -32600, "Invalid Request") + ',' +
					 error("3", -32601, "Method not found") + ',' +
					 error("4", -32602, "Invalid params") + ',' +
					 error("5", -32600, "Invalid Request") + ',' +
					 error("6", -32602, "Invalid params") + ',' +
					 error(null, -32600, "Invalid Request") + ',' +
					 "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":null}" +
					 ']',
			dispatch(discovering, batch));
	}

	@Test
	public void aBatchWhoseArrayStructureBreaksAfterTheDiscoverElementAnswersNothingAtAll() {
		// past a broken separator there are no element boundaries left to report against, so the whole
		// document is one -32700 and the discover element — already decoded — is never dispatched. The
		// oracle is the same break carrying an unregistered name: byte-identical, so "it was almost a
		// discover call" buys a prober nothing
		byte[] withDiscover = dispatchBytes(discovering, '[' + req("1", RPC_DISCOVER, null) + ",not-json]");
		byte[] withUnknown = dispatchBytes(discovering, '[' + req("1", UNREGISTERED, null) + ",not-json]");

		assertArrayEquals(withUnknown, withDiscover);
		assertEquals(error(null, -32700, "Parse error"), new String(withDiscover, UTF_8));
		assertFalse("no fragment of the document may escape through a batch that never dispatched",
			new String(withDiscover, UTF_8).contains("openrpc"));
	}

	// ---------------------------------------------------------------------------------------------------
	// 2 — every wrong params shape.
	//
	// The built-in entry has NO bespoke params rule: it is an ordinary ParamsCodec over a zero-arity
	// descriptor (FR-005). The adversarial question is therefore whether some shape slips past that codec —
	// and, separately, which shapes never reach it at all. Two refusals live at two different layers and must
	// not be confused:
	//   * §4.2 admits only an array, an object or null as `params`, so a BARE LITERAL is -32600 from the
	//     envelope decoder, before any handler lookup happens;
	//   * a structurally legal but non-empty `params` is the ordinary -32602 from the codec.
	// A third layer sits above both: a document over MAX_BODY_SIZE is -32001 and no name is ever read.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void everyNonEmptyStructuredParamsShapeIsTheOrdinaryInvalidParams() {
		for (String params : List.of(
			"[1]",
			"[null]",
			"[[]]",
			"[{}]",
			"[null,{},[],\"x\"]",
			"{\"anything\":1}",
			"{\"a\":{\"b\":{\"c\":1}}}",
			"[\"" + "a".repeat(900_000) + "\"]")) {     // just under maxBodySize, inside a legal array
			assertEquals("params " + abbreviate(params),
				error("1", -32602, "Invalid params"), dispatch(discovering, req("1", RPC_DISCOVER, params)));
		}
	}

	@Test
	public void aBareLiteralParamsIsRefusedByTheEnvelopeBeforeDiscoveryIsReached() {
		// §4.2: not an array, not an object, not null -> the document is not a Request object at all. The
		// existing functional test pins the number 42; the remaining literal kinds — and a 900kb one — are here
		for (String params : List.of(
			"true",
			"false",
			"\"x\"",
			"-1",
			"1.5",
			"\"" + "a".repeat(900_000) + '"')) {
			assertEquals("params " + abbreviate(params),
				error("1", -32600, "Invalid Request"), dispatch(discovering, req("1", RPC_DISCOVER, params)));
		}
	}

	@Test
	public void aDocumentOverMaxBodySizeNeverReachesTheDiscoveryEntry() {
		// the bound is the decoder's last line of defence and fires on the region's LENGTH, before the copy,
		// the UTF-8 scan, the depth scan and the parser — so the wire name is never read and the answer is
		// byte-identical to the same oversize document carrying an unregistered name
		String filler = "a".repeat((int) JsonRpcLimits.MAX_BODY_SIZE.toLong() + 1);
		byte[] withDiscover = dispatchBytes(discovering, req("1", RPC_DISCOVER, "[\"" + filler + "\"]"));
		byte[] withUnknown = dispatchBytes(discovering, req("1", UNREGISTERED, "[\"" + filler + "\"]"));

		assertArrayEquals(withUnknown, withDiscover);
		assertEquals(error(null, -32001, "Request too large"), new String(withDiscover, UTF_8));
	}

	@Test
	public void anOverDeepParamsIsNestingTooDeepRatherThanInvalidParams() {
		// the depth pre-scan runs before the parser, so it outranks both the envelope's params rule and the
		// zero-arity codec: an over-deep document aimed at rpc.discover is -32003 and the id is not recovered
		String deep = "[".repeat(JsonRpcLimits.MAX_JSON_DEPTH + 4) + "]".repeat(JsonRpcLimits.MAX_JSON_DEPTH + 4);
		assertEquals(error(null, -32003, "Nesting too deep"),
			dispatch(discovering, req("1", RPC_DISCOVER, deep)));
	}

	@Test
	public void everyLegalAbsenceOfParamsServesTheSameBytes() {
		// absent, null, [], {} and their whitespace-padded spellings are ONE outcome, not five that agree
		byte[] canonical = dispatchBytes(discovering, req("1", RPC_DISCOVER, null));
		for (String params : List.of("null", "[]", "{}", "[ ]", "{ }", "[\n\t]", "{\r\n}")) {
			assertArrayEquals("params " + abbreviate(params),
				canonical, dispatchBytes(discovering, req("1", RPC_DISCOVER, params)));
		}
	}

	@Test
	public void aRepeatedEnvelopeMemberCannotSmuggleADiscoverCallThrough() {
		// "the last occurrence wins" is unreachable by construction: a repeated defined member is -32600,
		// whichever occurrence names rpc.discover
		assertEquals(error("1", -32600, "Invalid Request"), dispatch(discovering,
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + UNREGISTERED + "\",\"method\":\"rpc.discover\"}"));
		assertEquals(error("1", -32600, "Invalid Request"), dispatch(discovering,
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.discover\",\"params\":[],\"params\":[]}"));
	}

	// ---------------------------------------------------------------------------------------------------
	// 3 — extreme identifiers.
	//
	// The answer is the largest document this dispatcher ever emits, and the id is echoed into it. The attack
	// is an id chosen to break that echo: the 64-bit extremes, a 200 000-character string, and a string of
	// control characters, an astral code point and an accented letter — the three classes a JSON string writer
	// gets wrong. Every case is checked by RE-DECODING the response and comparing JsonRpcId values, not by
	// string matching, so an id that round-trips through a different but equivalent spelling still passes and
	// an id that is silently truncated or re-encoded does not.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void everyExtremeIdIsEchoedBackUnchangedAlongsideTheWholeDocument() {
		String controlAndAstral = "\u0000\u0001\u001f\u007f\uD83D\uDE00 caf\u00e9 \u2028";
		List<IdCase> cases = List.of(
			new IdCase("0", new JsonRpcId.Num(0)),
			new IdCase("-0", new JsonRpcId.Num(0)),
			new IdCase("9223372036854775807", new JsonRpcId.Num(Long.MAX_VALUE)),
			new IdCase("-9223372036854775808", new JsonRpcId.Num(Long.MIN_VALUE)),
			new IdCase("null", new JsonRpcId.Null()),
			new IdCase("\"\"", new JsonRpcId.Str("")),
			new IdCase("\"" + "z".repeat(200_000) + '"', new JsonRpcId.Str("z".repeat(200_000))),
			new IdCase("\"\\u0000\\u0001\\u001f\\u007f\\ud83d\\ude00 caf\\u00e9 \\u2028\"",
				new JsonRpcId.Str(controlAndAstral)));

		for (IdCase idCase : cases) {
			byte[] response = dispatchBytes(discovering, req(idCase.rawId, RPC_DISCOVER, null));
			// the response is a real JSON-RPC document again — a broken id escape would surface here
			JsonRpcInput back = JsonRpcDecoder.decode(response);
			if (!(back instanceof JsonRpcResponse decoded)) {
				fail("id " + abbreviate(idCase.rawId) + " produced a response that does not re-decode: " + back);
				return;
			}
			assertEquals("id " + abbreviate(idCase.rawId) + " did not round trip",
				idCase.expected, decoded.id());
			assertEquals("id " + abbreviate(idCase.rawId) + " must not disturb the served document",
				new JsonRpcPayload.Raw(document, 0, document.length), decoded.result());
		}
	}

	@Test
	public void anIdThatIsNotAnIdIsRefusedBeforeDiscoveryIsReached() {
		// out of 64-bit range, fractional, exponential, structured, boolean — FR-036 refuses each, and the
		// malformed member IS the identifier, so nothing is recovered and the answer carries id null
		for (String rawId : List.of(
			"9223372036854775808",
			"-9223372036854775809",
			"1.0",
			"1e3",
			"true",
			"[]",
			"{}")) {
			assertEquals("id " + rawId,
				error(null, -32600, "Invalid Request"), dispatch(discovering, req(rawId, RPC_DISCOVER, null)));
		}
	}

	private record IdCase(String rawId, JsonRpcId expected) {}

	// ---------------------------------------------------------------------------------------------------
	// 4 — a batch at exactly maxBatchSize, then one over.
	//
	// This is the sharpest interaction between the new surface and a bound tuned against small bodies: 100
	// legal elements of ~50 bytes each turn ~5kb of request into ~420kb of response. Two properties are
	// asserted: the answers all share ONE array (the built-in payload is not copied per element, which is
	// what keeps the cost linear in output size rather than quadratic in anything), and the amplification is
	// bounded by maxBatchSize x document — there is no second, larger bound hiding anywhere.
	// At 101 elements the refusal must precede EVERY element, discovery included, which an inspector proves:
	// a processed element would have moved totalRequests.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aFullBatchOfDiscoverCallsIsServedFromOneSharedArray() {
		StringBuilder builder = new StringBuilder("[");
		for (int i = 0; i < JsonRpcLimits.MAX_BATCH_SIZE; i++) {
			if (i > 0) builder.append(',');
			builder.append(req(Integer.toString(i), RPC_DISCOVER, null));
		}
		String request = builder.append(']').toString();

		// the structured entry point, so the payload identity is observable at all
		JsonRpcInput input = JsonRpcDecoder.decode(request.getBytes(UTF_8));
		assertTrue(input instanceof JsonRpcBatch);
		JsonRpcOutput output = await(discovering.dispatch(input));
		if (!(output instanceof JsonRpcOutput.Batch batch)) {
			fail("a full batch of requests must answer with a batch, got " + output);
			return;
		}
		assertEquals(JsonRpcLimits.MAX_BATCH_SIZE, batch.messages().size());

		int index = 0;
		for (JsonRpcMessage message : batch.messages()) {
			JsonRpcResponse response = (JsonRpcResponse) message;
			assertEquals("request order must be preserved", new JsonRpcId.Num(index++), response.id());
			JsonRpcPayload payload = response.result();
			if (!(payload instanceof JsonRpcPayload.Raw raw)) {
				fail("element " + index + " did not answer with the pre-computed payload: " + payload);
				return;
			}
			// THE array, 100 times — not 100 copies of equal bytes
			assertSame("element " + index + " was answered from a copy of the document",
				document, raw.view().array());
		}

		// and the same batch through the byte entry point: bounded amplification, nothing else
		byte[] response = dispatchBytes(discovering, request);
		long bound = (long) JsonRpcLimits.MAX_BATCH_SIZE * (document.length + 64) + 2;
		assertTrue("a full discovery batch turned " + request.length() + " request bytes into " +
				   response.length + " response bytes, above the maxBatchSize x document bound of " + bound,
			response.length <= bound);
		System.out.println("[018-4] " + JsonRpcLimits.MAX_BATCH_SIZE + " x rpc.discover: " + request.length() +
						   " request bytes -> " + response.length + " response bytes (x" +
						   (response.length / request.length()) + ", bounded by maxBatchSize)");
	}

	@Test
	public void oneElementOverMaxBatchSizeRefusesBeforeAnyElementIsProcessed() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher wired = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.withDiscovery(INFO)
			.withInspector(inspector)
			.build();

		StringBuilder builder = new StringBuilder("[");
		for (int i = 0; i <= JsonRpcLimits.MAX_BATCH_SIZE; i++) {
			if (i > 0) builder.append(',');
			builder.append(req(Integer.toString(i), RPC_DISCOVER, null));
		}

		assertEquals(error(null, -32002, "Batch too large"), dispatch(wired, builder.append(']').toString()));

		// the proof that nothing ran: an element that had been dispatched would have moved these
		assertEquals(0, inspector.getTotalRequests().getTotalCount());
		assertEquals(0, inspector.getTotalErrors().getTotalCount());
		assertEquals(1, inspector.getMalformedDocuments().getTotalCount());
		JsonRpcMethodStats discover = inspector.getMethodStats().get(RPC_DISCOVER);
		assertNotNull("rpc.discover must own a row on a discovery-enabled dispatcher", discover);
		assertEquals(0, discover.getSuccessfulRequests().getTotalCount());
		assertEquals(0, discover.getFailedRequests().getTotalCount());
	}

	// ---------------------------------------------------------------------------------------------------
	// 5 — wire-name confusion.
	//
	// The lookup is a Map.get on the decoded String. That is the whole implementation, and these assertions
	// exist to keep it that way: no trim, no case folding, no normalisation, no separator tolerance. Every
	// near miss must be byte-identical to an arbitrary unregistered name, because a prober that can tell
	// "close to a real name" from "nothing like a real name" has an oracle.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void everyNearMissSpellingIsByteIdenticalToAnArbitraryUnregisteredName() {
		byte[] oracle = dispatchBytes(discovering, req("1", UNREGISTERED, null));

		for (String rawName : List.of(
			"Rpc.discover",
			"rpc.Discover",
			"RPC.DISCOVER",
			"rpc.discover ",             // trailing space
			" rpc.discover",             // leading space
			"rpc.discover\\u0000",       // embedded NUL, JSON-escaped
			"\\u0000rpc.discover",
			"rpc/discover",
			"rpc..discover",
			"rpc.discover.",
			"rpc_discover",
			"rpc.discoverr",
			"rpc.discove",
			"rpc.\\u0064iscover\\u0000") // the escaped spelling PLUS a NUL: still not the name
		) {
			assertArrayEquals("'" + rawName + "' must be indistinguishable from an unregistered name",
				oracle, dispatchBytes(discovering, req("1", rawName, null)));
		}
	}

	@Test
	public void anEscapedSpellingOfTheNameIsTheNameAndIsCountedOnItsOwnRow() {
		// RFC 8259: "rpc.disc\u006fver" and "rpc.discover" are the SAME JSON string, so the lookup matches and
		// the document is served. Pinned deliberately, with its consequence spelled out: a byte-level filter
		// upstream that blacklists the literal sequence rpc.discover does NOT close this endpoint. The only
		// switch that closes it is not calling withDiscovery(...).
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher wired = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.withDiscovery(INFO)
			.withInspector(inspector)
			.build();

		assertArrayEquals(dispatchBytes(wired, req("1", RPC_DISCOVER, null)),
			dispatchBytes(wired, req("1", "rpc.disc\\u006fver", null)));

		// and it lands on the canonical row — no row was created from wire text (FR-034)
		assertEquals(wired.wireNames(), inspector.getMethodStats().keySet());
		assertEquals(2, inspector.getMethodStats().get(RPC_DISCOVER).getSuccessfulRequests().getTotalCount());
		assertEquals(0, inspector.getMethodNotFound().getTotalCount());
	}

	@Test
	public void aDisabledDispatcherAnswersEveryNearMissAndTheNameItselfAlike() {
		// with discovery off there is nothing to probe for: the name, its escaped spelling and every near miss
		// are one answer, and it is the answer any unregistered name gets
		byte[] oracle = dispatchBytes(plain, req("1", UNREGISTERED, null));
		for (String rawName : List.of(RPC_DISCOVER, "rpc.disc\\u006fver", "Rpc.discover", "rpc.discover ")) {
			assertArrayEquals("'" + rawName + "' on a discovery-disabled dispatcher",
				oracle, dispatchBytes(plain, req("1", rawName, null)));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// 6 — rapid alternating builder cycles.
	//
	// The built-in entry is registered into an INSTANCE field of an inner-class builder, and the generated
	// bytes are held on the dispatcher instance. Everything about that is per-instance by construction — which
	// is exactly the kind of claim that is worth executing rather than reading. 500 dispatchers, alternating
	// enabled/disabled, each with an info nobody else uses: an answer carrying a neighbour's title, or a
	// -32601 from a dispatcher that had discovery on, would mean a static leaked somewhere.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void fiveHundredAlternatingBuildsEachAnswerWithTheirOwnInfoAndNobodyElses() {
		byte[] disabledOracle = null;
		long start = System.nanoTime();
		for (int i = 0; i < 500; i++) {
			boolean enabled = (i & 1) == 0;
			JsonRpcDispatcher.Builder builder = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService(ReferenceApi.class, new ReferenceApiImpl());
			if (enabled) builder.withDiscovery("API-" + i, "1.0." + i);
			JsonRpcDispatcher dispatcher = builder.build();

			byte[] answer = dispatchBytes(dispatcher, req("1", RPC_DISCOVER, null));
			if (enabled) {
				assertSame("iteration " + i + " must serve its own array",
					dispatcher.discoveryDocument(), servedArray(dispatcher));
				String text = new String(answer, UTF_8);
				assertTrue("iteration " + i + " served someone else's info: " + text.substring(0, 120),
					text.contains("\"title\":\"API-" + i + "\",\"version\":\"1.0." + i + '"'));
				assertEquals("exactly one info object", text.indexOf("\"title\""), text.lastIndexOf("\"title\""));
			} else {
				assertNull(dispatcher.discoveryDocument());
				if (disabledOracle == null) disabledOracle = dispatchBytes(dispatcher, req("1", UNREGISTERED, null));
				assertArrayEquals("iteration " + i + " leaked a previous build's discovery",
					disabledOracle, answer);
			}
		}
		System.out.println("[018-6] 500 alternating dispatcher builds + dispatches in " +
						   (System.nanoTime() - start) / 1_000_000 + "ms");
	}

	// ---------------------------------------------------------------------------------------------------
	// 7 — CHARACTERIZATION: no defensive copy, anywhere.
	//
	// discoveryDocument()'s Javadoc states it: the array is a build-time constant shared with the frozen
	// handler table, returned as-is because copying it per call would put an allocation back on a path this
	// feature made allocation-free — and "it must not be modified" is a caller obligation, not a defence.
	// This test PINS that, consequence included. It is not a bug report: a future change to copy-on-return
	// would be a legitimate design choice, and it must show up here as a deliberate behaviour change rather
	// than pass unnoticed.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theServedArrayIsTheVeryArrayDiscoveryDocumentHandsOut() {
		JsonRpcDispatcher subject = discovering(new OpenRpcInfo("Mutable API", "1"));
		byte[] handedOut = subject.discoveryDocument();
		assertNotNull(handedOut);

		// (a) the accessor is not copying
		assertSame(handedOut, subject.discoveryDocument());
		// (b) the frozen table's payload is backed by the same array
		assertSame(handedOut, servedArray(subject));

		// (c) and therefore a write through the accessor is visible in the next answer. Restored immediately:
		// the point is the aliasing, not leaving a corrupted fixture behind
		int index = new String(handedOut, UTF_8).indexOf("Mutable API");
		assertTrue("the title must be findable to mutate it", index > 0);
		byte original = handedOut[index];
		try {
			handedOut[index] = 'X';
			assertTrue("a write through discoveryDocument() must reach the served answer — there is no copy",
				dispatch(subject, req("1", RPC_DISCOVER, null)).contains("\"title\":\"Xutable API\""));
		} finally {
			handedOut[index] = original;
		}
		assertTrue("restoring the byte restores the answer",
			dispatch(subject, req("1", RPC_DISCOVER, null)).contains("\"title\":\"Mutable API\""));
	}

	// ---------------------------------------------------------------------------------------------------
	// 8 — bytes that ALMOST decode as a valid rpc.discover request.
	//
	// A prober's cheapest oracle is a difference in how a server fails. Every garbling below is dispatched
	// twice — once naming rpc.discover, once naming an unregistered method — and the two answers must be the
	// same bytes. "Almost a discovery call" must be worth exactly nothing.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void everyAlmostDiscoverGarblingFailsExactlyAsTheSameGarblingOfAnUnknownName() {
		List<String> garblings = List.of(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s\"",           // truncated mid-object
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s\",",          // truncated after a comma
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s\",}",         // trailing comma
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s\"}}",         // one brace too many
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s\"} trailing", // trailing data
			"{{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s\"}",         // one brace too few
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s}",            // unterminated name
			"{\"jsonrpc\":\"2.0\" \"id\":1,\"method\":\"%s\"}",          // missing separator
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s\"}",         // unterminated batch
			"\uFEFF{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"%s\"}");   // a leading BOM

		for (String garbling : garblings) {
			byte[] withDiscover = dispatchBytes(discovering, String.format(garbling, RPC_DISCOVER));
			byte[] withUnknown = dispatchBytes(discovering, String.format(garbling, UNREGISTERED));
			assertArrayEquals("garbling " + abbreviate(garbling), withUnknown, withDiscover);
			assertEquals("garbling " + abbreviate(garbling),
				error(null, -32700, "Parse error"), new String(withDiscover, UTF_8));
		}
	}

	@Test
	public void aTruncatedDiscoverDocumentAnsweredByADisabledDispatcherIsTheSameBytesToo() {
		// the -32700 path never reaches the handler table, so enabling discovery cannot change it — asserted
		// rather than assumed, because it is the one comparison a prober can make for free
		for (String garbling : List.of(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.discover\"",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.discover\",}")) {
			assertArrayEquals(dispatchBytes(plain, garbling), dispatchBytes(discovering, garbling));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// A — a hostile OpenRpcInfo.
	//
	// The info is the ONE part of the document an application supplies verbatim, and the document is emitted
	// to every peer that asks. If the title were written raw, a title could forge methods[] entries into the
	// served document — the JSON equivalent of an SQL injection, aimed at whatever tooling consumes the
	// document. It is written through a JsonCodec, so it is escaped; asserted here, on the served answer.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aTitleCraftedToForgeMethodsIsEscapedRatherThanEmitted() {
		OpenRpcInfo hostile = new OpenRpcInfo(
			"evil\",\"methods\":[{\"name\":\"backdoor\",\"params\":[]}],\"junk\":\"",
			"1.0\t</script>\\\u0001");
		JsonRpcDispatcher subject = discovering(hostile);

		byte[] response = dispatchBytes(subject, req("1", RPC_DISCOVER, null));
		// the answer is still one well-formed JSON-RPC response, and it re-decodes as such
		JsonRpcInput back = JsonRpcDecoder.decode(response);
		assertTrue("a hostile title must not break the document: " + back, back instanceof JsonRpcResponse);

		String text = new String(response, UTF_8);
		assertTrue("the quote in the title must be escaped", text.contains("evil\\\",\\\"methods\\\":"));
		assertTrue("the tab must be escaped", text.contains("1.0\\t"));
		assertTrue("the control character must be escaped", text.contains("\\u0001"));
		assertFalse("the forged method must not appear as a real member",
			text.contains("\"name\":\"backdoor\""));
		// exactly one methods member — the forged one is inside a string
		assertEquals(text.indexOf("\"methods\":["), text.lastIndexOf("\"methods\":["));
	}

	@Test
	public void aTitleThatCannotBeEncodedFailsAtBuildRatherThanAtTheFirstCall() {
		// an unpaired surrogate has no UTF-8 encoding. Generation is EAGER — one call at build() — so this is
		// a startup failure, and no dispatcher ever exists that would answer rpc.discover with broken bytes or
		// with a -32603. Fail-closed, and the whole point of pre-computing the document
		try {
			discovering(new OpenRpcInfo("lone-\uD800-surrogate", "1.0"));
			fail("a title that cannot be encoded must not produce a running dispatcher");
		} catch (RuntimeException expected) {
			assertTrue("the failure must name the offending code point: " + expected,
				expected.getMessage() != null && expected.getMessage().toLowerCase().contains("d800"));
		}
		// and the ordinary configuration still builds afterwards — nothing was left half-initialised statically
		assertNotNull(discovering(INFO).discoveryDocument());
	}

	// ---------------------------------------------------------------------------------------------------
	// B — CHARACTERIZATION: the document outgrows the line's own inbound bound.
	//
	// MAX_BODY_SIZE bounds what this stack will READ. Nothing anywhere bounds what it WRITES, which is
	// ordinarily fine — a response is as big as the value a service returned. The discovery answer is the
	// exception worth pinning: it grows with the number of registered methods and their type graphs, nobody
	// chooses its size per call, and once it crosses the bound the answer is undeliverable to any peer running
	// the same defaults. This test reaches that state through a large title (cheap, and exactly equivalent at
	// the byte level to a service with enough methods) and asserts BOTH halves: the dispatcher serves it
	// happily, and a conforming peer refuses it -32001.
	//
	// Reported, not "fixed": adding an outbound bound is a behaviour change with a CHANGELOG entry and belongs
	// with the per-instance limits feature 09 owns, not in an adversarial test.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aDocumentLargerThanMaxBodySizeIsServedAndRefusedByAConformingPeer() {
		int over = (int) JsonRpcLimits.MAX_BODY_SIZE.toLong() + 100_000;
		JsonRpcDispatcher subject = discovering(new OpenRpcInfo("t".repeat(over), "1.0"));

		byte[] generated = subject.discoveryDocument();
		assertNotNull(generated);
		assertTrue("the fixture must actually cross the bound: " + generated.length,
			generated.length > JsonRpcLimits.MAX_BODY_SIZE.toLong());

		// the dispatcher serves it without complaint — there is no outbound bound
		byte[] response = dispatchBytes(subject, req("1", RPC_DISCOVER, null));
		assertTrue(response.length > generated.length);

		// ... and the same bytes, handed to this line's own decoder as a peer would, are refused
		JsonRpcInput asAPeerSeesIt = JsonRpcDecoder.decode(response);
		if (!(asAPeerSeesIt instanceof JsonRpcMalformed malformed)) {
			fail("expected a peer to refuse the oversize answer, got " + asAPeerSeesIt);
			return;
		}
		assertEquals(-32001, malformed.error().code());
		System.out.println("[018-B] a " + generated.length + "-byte discovery document produces a " +
						   response.length + "-byte answer; MAX_BODY_SIZE is " +
						   JsonRpcLimits.MAX_BODY_SIZE.toLong() + ", so a conforming peer answers -32001");
	}

	// ---------------------------------------------------------------------------------------------------
	// C — degenerate but legal configurations of the new builder method.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void discoveryWithNoServicesAtAllIsLegalAndTheWholeSurfaceIsTheDocument() {
		JsonRpcDispatcher empty = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withDiscovery("Empty", "0")
			.build();

		assertEquals(Set.of(RPC_DISCOVER), empty.wireNames());
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" +
					 "{\"openrpc\":\"1.4.0\",\"info\":{\"title\":\"Empty\",\"version\":\"0\"},\"methods\":[]}}",
			dispatch(empty, req("1", RPC_DISCOVER, null)));
		assertEquals(error("1", -32601, "Method not found"), dispatch(empty, req("1", UNREGISTERED, null)));
	}

	@Test
	public void theLastWithDiscoveryWins() {
		JsonRpcDispatcher subject = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl())
			.withDiscovery("first", "1")
			.withDiscovery(new OpenRpcInfo("second", "2"))
			.build();

		String text = new String(subject.discoveryDocument(), UTF_8);
		assertTrue(text, text.contains("\"title\":\"second\",\"version\":\"2\""));
		assertFalse(text, text.contains("first"));
	}

	@Test
	public void withDiscoveryRefusesNull() {
		JsonRpcDispatcher.Builder builder = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(ReferenceApi.class, new ReferenceApiImpl());
		try {
			builder.withDiscovery((OpenRpcInfo) null);
			fail("withDiscovery(null) must be refused");
		} catch (NullPointerException expected) {
			// the info is REQUIRED by OpenRPC; presence is the switch, so there is no null-means-off reading
		}
		try {
			builder.withDiscovery(null, "1");
			fail("withDiscovery(null, version) must be refused");
		} catch (NullPointerException expected) {
			// OpenRpcInfo's own compact constructor
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	/** The array actually backing the frozen table's {@code rpc.discover} payload, reached through dispatch. */
	private static byte[] servedArray(JsonRpcDispatcher dispatcher) {
		JsonRpcOutput output = await(dispatcher.dispatch(
			JsonRpcDecoder.decode(req("1", RPC_DISCOVER, null).getBytes(UTF_8))));
		JsonRpcResponse response = (JsonRpcResponse) ((JsonRpcOutput.Single) output).message();
		return ((JsonRpcPayload.Raw) response.result()).view().array();
	}

	/**
	 * One request document.
	 *
	 * @param rawId     the {@code id} member's value as raw JSON — {@code 1}, {@code null}, {@code "x"}, …
	 * @param rawName   the {@code method} string's <b>interior</b>, already JSON-escaped, so a test may spell
	 *                  a name this decoder must not normalise
	 * @param rawParams the {@code params} member's value as raw JSON, or {@code null} to omit the member
	 */
	private static String req(String rawId, String rawName, @Nullable String rawParams) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + rawId + ",\"method\":\"" + rawName + '"' +
			   (rawParams == null ? "" : ",\"params\":" + rawParams) + '}';
	}

	/** The canonical error document, for an {@code id} spelled as raw JSON ({@code null} means the literal). */
	private static String error(@Nullable String rawId, int code, String message) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + (rawId == null ? "null" : rawId) +
			   ",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}";
	}

	private static byte[] dispatchBytes(JsonRpcDispatcher dispatcher, String document) {
		byte[] response = await(dispatcher.dispatch(document.getBytes(UTF_8)));
		assertNotNull(response);
		return response;
	}

	private static String dispatch(JsonRpcDispatcher dispatcher, String document) {
		return new String(dispatchBytes(dispatcher, document), UTF_8);
	}

	private static String abbreviate(String s) {
		return s.length() <= 60 ? s : s.substring(0, 40) + "…[" + s.length() + " chars]";
	}
}
