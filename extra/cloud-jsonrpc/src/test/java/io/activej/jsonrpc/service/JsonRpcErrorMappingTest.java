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

import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.service.fixtures.FailingApi;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static io.activej.jsonrpc.service.fixtures.FailingApiImpl.APPLICATION_ERROR_JSON;
import static io.activej.jsonrpc.service.fixtures.FailingApiImpl.SECRET;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * User story 5 — a service method fails and the wire learns <b>nothing</b> it was not deliberately told
 * (FR-046, FR-046a, FR-047, FR-047a, FR-048, SC-007).
 * <p>
 * Every assertion here is over the emitted document <b>byte for byte</b>. That is the only form of the claim
 * that is worth anything: "contains {@code -32603}" would pass just as happily for a document that also
 * carried the exception's class name, its message and a stack frame. The fixture's failures all carry
 * {@link FailingApiImpl#SECRET}, so the negative assertions have something real to look for.
 *
 * <h2>The two codec factories</h2>
 * {@code null} is not special-cased anywhere in this feature; it is handed to the declared codec, which
 * decides (FR-046a). Both dispatchers below are built over the <b>same</b> interface and the <b>same</b>
 * implementation, and differ only in whether the resolved {@code String} codec accepts {@code null} — which
 * is exactly the variable FR-046a says governs the outcome.
 */
public class JsonRpcErrorMappingTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private static final String INTERNAL_ERROR_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
	private static final String INVALID_PARAMS_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}";

	/** Refuses {@code null} for a {@code String}, which is the repository-wide default. */
	private static final JsonCodecFactory STRICT = JsonCodecFactory.defaultInstance();
	/** Accepts {@code null} for a {@code String} — the codec-level opt-in {@code @JsonNullable} stands for. */
	private static final JsonCodecFactory NULLABLE = JsonCodecFactory.defaultInstance().rebuild()
		.with(String.class, ctx -> JsonCodecs.ofString().nullable())
		.build();

	private FailingApiImpl implementation;
	private JsonRpcDispatcher dispatcher;
	private JsonRpcDispatcher nullableDispatcher;

	@Before
	public void setUp() {
		implementation = new FailingApiImpl();
		// building at all is the FR-047a assertion: FailingApi.thrownJsonRpc() declares
		// "throws JsonRpcException", and a contract that treated a throws clause as a violation would
		// throw JsonRpcContractException right here, before a single test body ran
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(FailingApi.class, implementation)
			.withCodecFactory(STRICT)
			.build();
		nullableDispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(FailingApi.class, implementation)
			.withCodecFactory(NULLABLE)
			.build();
	}

	// ---------------------------------------------------------------------------------------------------
	// T054 — an unplanned failure is exactly -32603, byte for byte (FR-048, SC-007).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aThrownExceptionIsExactlyInternalErrorAndNothingElse() {
		byte[] response = dispatch("fail.thrown");

		assertArrayEquals(INTERNAL_ERROR_RESPONSE.getBytes(UTF_8), response);
		assertDisclosesNothing(response);
		assertEquals("the implementation is reached — the failure is its own, not a routing one",
			List.of("thrown()"), implementation.invocations());
	}

	@Test
	public void anExceptionallyCompletedPromiseIsTheSameDocumentAsAThrownOne() {
		byte[] thrown = dispatch("fail.thrown");
		implementation.invocations().clear();
		byte[] failed = dispatch("fail.failedPromise");

		assertArrayEquals("the two routes to FR-048 must be indistinguishable on the wire", thrown, failed);
		assertArrayEquals(INTERNAL_ERROR_RESPONSE.getBytes(UTF_8), failed);
		assertDisclosesNothing(failed);
	}

	@Test
	public void noPartOfTheExceptionReachesTheDocument() {
		String response = dispatchToString("fail.thrown");

		assertFalse("the exception message must not appear", response.contains(SECRET));
		assertFalse("no fragment of the message either", response.contains("hunter2"));
		assertFalse("the exception class name must not appear", response.contains("IllegalStateException"));
		assertFalse("nor its package", response.contains("java.lang"));
		assertFalse("no stack frame of this implementation", response.contains("io.activej"));
		assertFalse("no stack frame marker at all", response.contains("\tat "));
		assertFalse("a -32603 carries no data member (FR-048)", response.contains("\"data\""));
	}

	// ---------------------------------------------------------------------------------------------------
	// T055 — a deliberate JsonRpcException travels verbatim, by either route (FR-047, FR-047a); a null
	// where a Promise was declared is a failed invocation (FR-046).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aPromiseFailedWithJsonRpcExceptionRoundTripsCodeMessageAndDataVerbatim() {
		String response = dispatchToString("fail.failedWithJsonRpc");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":" + APPLICATION_ERROR_JSON + '}', response);
		assertFalse("the exception's local message stays local (FR-089)", response.contains(SECRET));
	}

	@Test
	public void aThrownJsonRpcExceptionProducesAnIdenticalDocument() {
		byte[] failed = dispatch("fail.failedWithJsonRpc");
		byte[] thrown = dispatch("fail.thrownJsonRpc");

		assertArrayEquals(
			"the checked-throw route and the failed-promise route are one behaviour, not two (FR-047a)",
			failed, thrown);
		assertEquals(List.of("failedWithJsonRpc()", "thrownJsonRpc()"), implementation.invocations());
	}

	@Test
	public void anApplicationErrorKeepsItsOwnCodeOutsideTheReservedRange() {
		String response = dispatchToString("fail.thrownJsonRpc");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":" +
					 "{\"code\":429,\"message\":\"Too many requests\",\"data\":{\"retryAfter\":30}}}", response);
	}

	@Test
	public void aNullWhereAPromiseWasDeclaredIsAFailedInvocationRatherThanAPropagatedNpe() {
		byte[] response = dispatch("fail.nullPromise");

		assertArrayEquals(INTERNAL_ERROR_RESPONSE.getBytes(UTF_8), response);
		assertDisclosesNothing(response);
		assertEquals("the method really was invoked; it is its return value that is wrong",
			List.of("nullPromise()"), implementation.invocations());
		assertFalse("nothing of the NullPointerException may surface",
			new String(response, UTF_8).contains("Null"));
	}

	// ---------------------------------------------------------------------------------------------------
	// T057 — null arguments and null results are the declared codec's business, and a codec's refusal is
	// the ordinary -32602 / -32603 (FR-046a).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aNullArgumentReachesADeclaredCodecThatAcceptsIt() {
		String response = dispatchToString(nullableDispatcher,
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.echo\",\"params\":{\"value\":null}}");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"<null>\"}", response);
		assertEquals("the null is handed to the method unchanged, not replaced or dropped",
			Collections.singletonList(null), implementation.arguments());
	}

	@Test
	public void aNullArgumentIsAlsoAcceptedPositionally() {
		String response = dispatchToString(nullableDispatcher,
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.echo\",\"params\":[null]}");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"<null>\"}", response);
		assertEquals(Collections.singletonList(null), implementation.arguments());
	}

	@Test
	public void aCodecRefusingANullArgumentIsTheOrdinaryInvalidParams() {
		String named = dispatchToString(dispatcher,
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.echo\",\"params\":{\"value\":null}}");
		String positional = dispatchToString(dispatcher,
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.echo\",\"params\":[null]}");

		assertEquals("a refused null is -32602 like any other refused value, not a case of its own",
			INVALID_PARAMS_RESPONSE, named);
		assertEquals(INVALID_PARAMS_RESPONSE, positional);
		assertEquals("a params failure never reaches the implementation", List.of(),
			implementation.invocations());
		assertFalse("no data member on a params failure", named.contains("\"data\""));
	}

	@Test
	public void aNullResultReachesADeclaredCodecThatAcceptsIt() {
		String response = dispatchToString(nullableDispatcher,
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.nullResult\"}");

		assertEquals("a null the result codec accepts is the JSON literal null, a present value",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}", response);
	}

	@Test
	public void aCodecRefusingANullResultIsTheOrdinaryInternalError() {
		byte[] response = dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.nullResult\"}");

		assertArrayEquals("a refused null result is -32603 like any other encoding failure",
			INTERNAL_ERROR_RESPONSE.getBytes(UTF_8), response);
		assertDisclosesNothing(response);
		assertEquals("the method ran; only its value could not be rendered",
			List.of("nullResult()"), implementation.invocations());
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	private byte[] dispatch(String method) {
		return dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}");
	}

	private String dispatchToString(String method) {
		return new String(dispatch(method), UTF_8);
	}

	private static byte[] dispatch(JsonRpcDispatcher dispatcher, String document) {
		byte[] response = await(dispatcher.dispatch(document.getBytes(UTF_8)));
		assertNotNull(response);
		return response;
	}

	private static String dispatchToString(JsonRpcDispatcher dispatcher, String document) {
		return new String(dispatch(dispatcher, document), UTF_8);
	}

	/** The negative half of SC-007, applied to a raw document rather than to a parsed one. */
	private static void assertDisclosesNothing(byte[] response) {
		String text = new String(response, UTF_8);
		assertFalse(text, text.contains(SECRET));
		assertFalse(text, text.contains("Exception"));
		assertFalse(text, text.contains("io.activej"));
		assertFalse(text, text.contains("\"data\""));
		assertFalse("not even as raw bytes outside the JSON text",
			indexOf(response, SECRET.getBytes(UTF_8)) >= 0);
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}
}
