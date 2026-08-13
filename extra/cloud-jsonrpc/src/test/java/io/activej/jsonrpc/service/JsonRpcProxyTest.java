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

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.service.fixtures.InMemoryTransport;
import io.activej.jsonrpc.service.fixtures.User;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User story 2 — {@code api.getUser(42)} is encoded, sent, correlated by {@code id} and completed on the
 * reactor thread (FR-059…FR-080, FR-089…FR-090).
 * <p>
 * The peer is a real {@link JsonRpcDispatcher} joined by {@link InMemoryTransport}: both halves of the
 * feature, in one process, with no socket anywhere.
 */
public class JsonRpcProxyTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private UserApiImpl implementation;
	private ProxyApiImpl proxyImplementation;
	private InMemoryTransport transport;
	private JsonRpcClient client;
	private UserApi api;

	@Before
	public void setUp() {
		implementation = new UserApiImpl();
		proxyImplementation = new ProxyApiImpl();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, implementation)
			.withService(ProxyApi.class, proxyImplementation)
			.build();
		transport = InMemoryTransport.create(dispatcher::dispatch);
		client = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport).build();
		api = client.proxy(UserApi.class);
	}

	// ---------------------------------------------------------------------------------------------------
	// T041 — US2 acceptance scenarios 1–5.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void scenario1_oneCallIsOneDocumentAndOneResult() {
		Promise<User> promise = api.getUser(42);

		assertEquals("exactly one document per call (FR-080)", 1, transport.sentDocuments().size());
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}",
			transport.sentText().get(0));
		assertEquals(new User(42, "user-42"), await(promise));
		assertEquals(List.of("getUser(42)"), implementation.invocations());
	}

	@Test
	public void scenario2_thePromiseIsPendingUntilTheResponseArrives() {
		transport.startHolding();

		Promise<User> promise = api.getUser(42);

		assertFalse("the call is not answered until the response is delivered", promise.isComplete());
		assertEquals(1, client.inFlightCount());
		assertEquals(1, transport.heldCount());

		transport.releaseInOrder();

		assertTrue(promise.isComplete());
		assertEquals(0, client.inFlightCount());
		assertEquals(new User(42, "user-42"), await(promise));
	}

	@Test
	public void scenario3_theResultIsDecodedOnTheReactorThread() {
		Reactor reactor = Reactor.getCurrentReactor();
		boolean[] onReactorThread = {false};

		await(api.getUser(42).whenResult(user -> onReactorThread[0] = reactor.inReactorThread()));

		assertTrue("the value is produced on the reactor thread (FR-079)", onReactorThread[0]);
	}

	@Test
	public void scenario4_anErrorResponseProducesAJsonRpcExceptionCarryingCodeMessageAndData()
		throws MalformedDataException {
		ProxyApi proxy = client.proxy(ProxyApi.class);

		Exception e = awaitException(proxy.fail());

		assertTrue(e.toString(), e instanceof JsonRpcException);
		JsonRpcError error = ((JsonRpcException) e).getError();
		assertEquals(42, error.code());
		assertEquals("the-application-said-so", error.message());
		assertEquals("detail", error.data().decode(JsonCodecs.ofString()));
	}

	@Test
	public void scenario5_aNotificationEmitsADocumentWithNoId() {
		api.touch(42);

		assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[42]}"),
			transport.sentText());
		assertEquals("a notification bypasses the correlation table (FR-071)", 0, client.inFlightCount());
		assertEquals(List.of("touch(42)"), implementation.invocations());
	}

	@Test
	public void aPromiseVoidNotificationCompletesWhenTheDocumentIsWritten() {
		ProxyApi proxy = client.proxy(ProxyApi.class);

		Promise<Void> sent = proxy.ping();

		assertTrue("the promise of a Promise<Void> notification is the transport's send promise (FR-071)",
			sent.isResult());
		assertEquals(0, client.inFlightCount());
	}

	// ---------------------------------------------------------------------------------------------------
	// T042 — the locally answered methods (FR-075).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void equalsHashCodeAndToStringAreAnsweredLocally() {
		ProxyApi proxy = client.proxy(ProxyApi.class);

		assertTrue(proxy.equals(proxy));
		//noinspection AssertBetweenInconvertibleTypes
		assertFalse(proxy.equals(api));
		assertEquals(System.identityHashCode(proxy), proxy.hashCode());
		assertNotNull(proxy.toString());
		assertTrue(proxy.toString(), proxy.toString().contains("ProxyApi"));

		assertEquals("no local method may produce a document", List.of(), transport.sentText());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void anUnannotatedDefaultMethodIsInvokedLocally() {
		ProxyApi proxy = client.proxy(ProxyApi.class);

		assertEquals("local", proxy.localHelper());

		assertEquals("an unannotated default method is the author's own helper (FR-023, FR-075)",
			List.of(), transport.sentText());
		assertEquals(0, client.inFlightCount());
	}

	@Test
	public void twoProxiesForTheSameInterfaceAreTheSameInstance() {
		// FR-063: the contract is resolved at most once per (client, interface)
		assertSame(client.proxy(ProxyApi.class), client.proxy(ProxyApi.class));
		assertSame(api, client.proxy(UserApi.class));
	}

	// ---------------------------------------------------------------------------------------------------
	// T043 — parameter style (FR-089, FR-089a, FR-090).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void paramsArePositionalByDefault() {
		api.getUser(42);

		assertTrue(transport.sentText().get(0), transport.sentText().get(0).contains("\"params\":[42]"));
	}

	@Test
	public void aZeroArgumentCallOmitsParamsEntirely() {
		ProxyApi proxy = client.proxy(ProxyApi.class);

		await(proxy.nullary());

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"proxy.nullary\"}", transport.sentText().get(0));
	}

	@Test
	public void namedParamsAreSelectablePerClient() {
		JsonRpcClient named = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withParamStyle(JsonRpcParamStyle.NAMED)
			.build();

		assertEquals(new User(42, "user-42"), await(named.proxy(UserApi.class).getUser(42)));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":{\"id\":42}}",
			transport.sentText().get(0));
	}

	@Test
	public void aZeroArgumentCallOmitsParamsInNamedStyleToo() {
		JsonRpcClient named = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withParamStyle(JsonRpcParamStyle.NAMED)
			.build();

		await(named.proxy(ProxyApi.class).nullary());

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"proxy.nullary\"}", transport.sentText().get(0));
	}

	@Test
	public void namedStyleIsRefusedAtProxyTimeForAMethodWithAnUnannotatedParameter() {
		JsonRpcClient named = JsonRpcClient.builder(Reactor.getCurrentReactor(), transport)
			.withParamStyle(JsonRpcParamStyle.NAMED)
			.build();

		try {
			named.proxy(UnnamedApi.class);
			fail("named emission must be refused at proxy(...) time, not at the first call (FR-090)");
		} catch (JsonRpcContractException expected) {
			assertEquals(1, expected.violations().size());
			String violation = expected.violations().get(0);
			assertTrue(violation, violation.contains("mix"));
			assertTrue("the offending position must be named: " + violation, violation.contains("1"));
		}
	}

	@Test
	public void theSameInterfaceIsAcceptedInPositionalStyle() {
		// the refusal above is a property of the style, not of the interface
		assertNotNull(client.proxy(UnnamedApi.class));
	}

	// ---------------------------------------------------------------------------------------------------
	// T044 — return adaptation (FR-056, FR-062, FR-030).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aSynchronousResultIsAcceptedByTheDispatcher() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(SyncApi.class, new SyncApiImpl())
			.build();

		byte[] response = await(dispatcher.dispatch(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sync.now\"}".getBytes(UTF_8)));

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"now\"}", new String(response, UTF_8));
	}

	@Test
	public void aSynchronousResultIsRefusedByTheProxyNamingTheMethod() {
		try {
			client.proxy(SyncApi.class);
			fail("a proxy cannot answer synchronously without blocking the reactor (FR-062)");
		} catch (JsonRpcContractException expected) {
			assertEquals(1, expected.violations().size());
			assertTrue(expected.violations().get(0), expected.violations().get(0).contains("now"));
		}
	}

	@Test
	public void aPromiseVoidRoundTripsAsTheJsonLiteralNull() {
		ProxyApi proxy = client.proxy(ProxyApi.class);

		assertNull(await(proxy.clear()));

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"proxy.clear\"}", transport.sentText().get(0));
		assertEquals(0, client.inFlightCount());
	}

	// ---------------------------------------------------------------------------------------------------
	// Fixtures.
	// ---------------------------------------------------------------------------------------------------

	@JsonRpcService("proxy")
	public interface ProxyApi {
		@JsonRpcMethod("nullary")
		Promise<String> nullary();

		/** The one result shape that resolves to no codec at all (FR-030). */
		@JsonRpcMethod("clear")
		Promise<Void> clear();

		@JsonRpcMethod("fail")
		Promise<String> fail();

		/** A notification declared as {@code Promise<Void>}: completes when written, never when handled. */
		@JsonRpcNotification("ping")
		Promise<Void> ping();

		/** Unannotated, and therefore the author's own helper rather than a wire method (FR-023). */
		default String localHelper() {
			return "local";
		}
	}

	public static final class ProxyApiImpl implements ProxyApi {
		private int pings;

		@Override
		public Promise<String> nullary() {
			return Promise.of("nullary");
		}

		@Override
		public Promise<Void> clear() {
			return Promise.complete();
		}

		@Override
		public Promise<String> fail() {
			return Promise.ofException(new JsonRpcException(JsonRpcErrors.of(42, "the-application-said-so",
				JsonRpcPayload.encoded(JsonCodecs.ofString(), "detail"))));
		}

		@Override
		public Promise<Void> ping() {
			pings++;
			return Promise.complete();
		}

		@SuppressWarnings("unused")
		public int pings() {
			return pings;
		}
	}

	/** Parameter 1 carries no {@code @JsonRpcParam}: legal positionally, refused in named style (FR-090). */
	@JsonRpcService("unnamed")
	public interface UnnamedApi {
		@JsonRpcMethod("mix")
		Promise<String> mix(@JsonRpcParam("a") long a, String b);
	}

	/** A synchronous {@code T}: accepted by the dispatcher (FR-056), refused by the proxy (FR-062). */
	@JsonRpcService("sync")
	public interface SyncApi {
		@JsonRpcMethod("now")
		String now();
	}

	public static final class SyncApiImpl implements SyncApi {
		@Override
		public String now() {
			return "now";
		}
	}
}
