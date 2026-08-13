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

import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcEncoder;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcOutput;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User story 1 — an inbound document naming {@code user.get} reaches an implementation with decoded
 * arguments and its result comes back as a JSON-RPC response, with no transport in existence (FR-035…FR-045).
 * <p>
 * Every assertion is over a {@code byte[]} in and a {@code byte[]} out, which is the whole point: US1 is
 * deliverable before a socket exists.
 */
public class JsonRpcDispatcherTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private UserApiImpl implementation;
	private EdgeApiImpl edge;
	private JsonRpcDispatcher dispatcher;

	@Before
	public void setUp() {
		implementation = new UserApiImpl();
		edge = new EdgeApiImpl();
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, implementation)
			.withService(EdgeApi.class, edge)
			.build();
	}

	// ---------------------------------------------------------------------------------------------------
	// T021 — US1 acceptance scenarios 1–4.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void scenario1_aKnownMethodWithPositionalParamsIsInvokedAndAnswered() {
		String response = dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":42,\"name\":\"user-42\"}}", response);
		assertEquals(List.of("getUser(42)"), implementation.invocations());
	}

	@Test
	public void scenario2_anUnknownMethodIs32601AndInvokesNothing() {
		String response = dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.nope\",\"params\":[42]}");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}",
			response);
		assertEquals("nothing may be invoked for an unknown method (FR-041)", List.of(), implementation.invocations());
	}

	@Test
	public void scenario3_aNotificationProducesAZeroLengthDocumentAndStillInvokes() {
		byte[] response = await(dispatcher.dispatch(utf8(
			"{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[42]}")));

		assertEquals("a notification is answered with nothing at all, not with []", 0, response.length);
		assertEquals(List.of("touch(42)"), implementation.invocations());
	}

	@Test
	public void scenario4_namedParamsProduceAnIdenticalInvocation() {
		String positional = dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
		List<String> afterPositional = List.copyOf(implementation.invocations());

		String named = dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":{\"id\":42}}");

		assertEquals(positional, named);
		assertEquals(List.of("getUser(42)", "getUser(42)"), implementation.invocations());
		assertEquals(List.of("getUser(42)"), afterPositional);
	}

	@Test
	public void anUnknownNotificationIsSilentlyDroppedRatherThanAnswered() {
		byte[] response = await(dispatcher.dispatch(utf8("{\"jsonrpc\":\"2.0\",\"method\":\"user.nope\"}")));

		assertEquals(0, response.length);
		assertEquals(List.of(), implementation.invocations());
	}

	@Test
	public void exposesTheWireNamesItResolves() {
		assertEquals(
			Set.of("user.get", "user.touch",
				"edge.nullary", "edge.positional", "edge.two", "edge.echo", "edge.clear"),
			dispatcher.wireNames());
	}

	@Test
	public void aPromiseVoidResultIsTheJsonLiteralNullAndNeedsNoCodecForVoid() {
		// FR-030: the one result shape that resolves to no codec at all
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}",
			dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"edge.clear\"}"));
	}

	@Test
	public void aNotificationMethodCalledWithAnIdIsAnsweredWithANullResult() {
		// the wire decides whether an element expects an answer, not the Java declaration: a peer that sends
		// an id for a @JsonRpcNotification method is owed a response, and void has exactly one rendering
		String response = dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.touch\",\"params\":[42]}");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}", response);
		assertEquals(List.of("touch(42)"), implementation.invocations());
	}

	@Test
	public void wireNamesIsReadOnly() {
		try {
			dispatcher.wireNames().add("user.injected");
			throw new AssertionError("the wire-name set must be read-only (FR-039a)");
		} catch (UnsupportedOperationException expected) {
			// immutable after build() (FR-057)
		}
	}

	@Test
	public void theStructuredEntryPointAnswersTheSameThingAsTheDocumentEntryPoint() {
		JsonRpcInput input = JsonRpcDecoder.decode(
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[7]}"));

		JsonRpcOutput output = await(dispatcher.dispatch(input));

		assertTrue(output instanceof JsonRpcOutput.Single);
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":7,\"name\":\"user-7\"}}",
			new String(JsonRpcEncoder.encode(output), UTF_8));
	}

	@Test
	public void anInboundResponseProducesNothingAndIsNotAnError() {
		byte[] response = await(dispatcher.dispatch(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":7}")));

		assertEquals(0, response.length);
	}

	@Test
	public void aStringIdIsEchoedAsAString() {
		String response = dispatch("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"user.get\",\"params\":[1]}");

		assertTrue(response, response.contains("\"id\":\"abc\""));
	}

	// ---------------------------------------------------------------------------------------------------
	// T022 — parameter edge cases, all -32602, none leaking a decoder message (FR-042…FR-045).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void tooManyPositionalParamsIs32602() {
		assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1,2]}");
	}

	@Test
	public void tooFewPositionalParamsIs32602() {
		assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"edge.two\",\"params\":[1]}");
	}

	@Test
	public void aMissingNamedKeyIs32602() {
		assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"edge.two\",\"params\":{\"a\":1}}");
	}

	@Test
	public void anUnknownNamedKeyIs32602() {
		assertInvalidParams(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":{\"id\":1,\"extra\":2}}");
	}

	@Test
	public void namedParamsOnAPositionalOnlyMethodIs32602() {
		assertInvalidParams(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"edge.positional\",\"params\":{\"a\":1,\"b\":\"x\"}}");
		assertEquals("a method that cannot take named params must not be invoked", 0, edge.calls());
	}

	@Test
	public void aWrongParameterTypeIs32602() {
		assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[\"forty-two\"]}");
	}

	@Test
	public void absentParamsIsAcceptedForAZeroArityMethod() {
		String response = dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"edge.nullary\"}");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"nullary\"}", response);
	}

	@Test
	public void absentParamsIs32602ForANonZeroArityMethod() {
		assertInvalidParams("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\"}");
	}

	@Test
	public void anEmptyArrayOrObjectIsAcceptedForAZeroArityMethod() {
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"nullary\"}",
			dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"edge.nullary\",\"params\":[]}"));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"nullary\"}",
			dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"edge.nullary\",\"params\":{}}"));
	}

	@Test
	public void aParamsMemberThatIsNeitherArrayNorObjectNeverReachesTheMethod() {
		// the envelope layer already refuses a bare literal params with -32600; whichever error it is, the
		// implementation is not reached
		byte[] response = await(dispatcher.dispatch(
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":42}")));

		assertTrue(new String(response, UTF_8).contains("\"error\""));
		assertEquals(List.of(), implementation.invocations());
	}

	@Test
	public void noDecoderMessageIsCopiedIntoTheResponse() {
		String response = dispatch(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"edge.echo\",\"params\":{\"secret-key\":\"hunter2\"}}");

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}",
			response);
		assertFalse("no data member may be attached to a params failure (FR-045)", response.contains("\"data\""));
		assertFalse("nothing of the offending payload may be echoed", response.contains("hunter2"));
		assertFalse("nothing of the offending payload may be echoed", response.contains("secret-key"));
	}

	@Test
	public void aNullDecodedInputIsRejectedSymmetricallyWithANullDocument() {
		try {
			dispatcher.dispatch((JsonRpcInput) null);
			fail("dispatch(JsonRpcInput) must require its argument, exactly as dispatch(byte[]) does");
		} catch (NullPointerException expected) {}
	}

	// ---------------------------------------------------------------------------------------------------
	// Fixtures for the edge cases.
	// ---------------------------------------------------------------------------------------------------

	@JsonRpcService("edge")
	public interface EdgeApi {
		@JsonRpcMethod("nullary")
		Promise<String> nullary();

		/** No {@code @JsonRpcParam} anywhere: positional-only (FR-043). */
		@JsonRpcMethod("positional")
		Promise<String> positionalOnly(long a, String b);

		@JsonRpcMethod("two")
		Promise<String> two(@JsonRpcParam("a") long a, @JsonRpcParam("b") String b);

		@JsonRpcMethod("echo")
		Promise<String> echo(@JsonRpcParam("value") String value);

		/** The one result shape that resolves to no codec at all (FR-030). */
		@JsonRpcMethod("clear")
		Promise<Void> clear();
	}

	public static final class EdgeApiImpl implements EdgeApi {
		private int calls;

		@Override
		public Promise<String> nullary() {
			calls++;
			return Promise.of("nullary");
		}

		@Override
		public Promise<String> positionalOnly(long a, String b) {
			calls++;
			return Promise.of("positional:" + a + b);
		}

		@Override
		public Promise<String> two(long a, String b) {
			calls++;
			return Promise.of("two:" + a + b);
		}

		@Override
		public Promise<String> echo(String value) {
			calls++;
			return Promise.of(value);
		}

		@Override
		public Promise<Void> clear() {
			calls++;
			return Promise.complete();
		}

		public int calls() {
			return calls;
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	private String dispatch(String document) {
		byte[] response = await(dispatcher.dispatch(utf8(document)));
		assertNotNull(response);
		return new String(response, UTF_8);
	}

	private void assertInvalidParams(String document) {
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}",
			dispatch(document));
	}

	private static byte[] utf8(String s) {
		return s.getBytes(UTF_8);
	}
}
