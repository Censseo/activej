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

import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.service.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Set;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FR-037 — several services on one dispatcher: namespaces accumulate, and a wire name claimed by two
 * different interfaces fails at {@code build()} naming both.
 * <p>
 * The collision is a <b>build-time</b> failure rather than a first-call surprise, which is the whole reason
 * the contract is total: two services that would shadow each other cannot be started.
 */
public class JsonRpcCrossServiceTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@Test
	public void twoServicesAccumulateTheirNamespaces() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(UserApi.class, new UserApiImpl())
			.withService(OrderApi.class, new OrderApiImpl())
			.build();

		assertEquals(Set.of("user.get", "user.touch", "order.get"), dispatcher.wireNames());

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"id\":1,\"name\":\"user-1\"}}",
			dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1]}"));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"order-7\"}",
			dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"order.get\",\"params\":[7]}"));
	}

	@Test
	public void aWireNameClaimedByTwoInterfacesFailsAtBuildNamingBoth() {
		try {
			JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService(UserApi.class, new UserApiImpl())
				.withService(ShadowingUserApi.class, new ShadowingUserApiImpl())
				.build();
			fail("two services claiming user.get must not build");
		} catch (JsonRpcContractException e) {
			String message = e.getMessage();
			assertEquals(1, e.violations().size());
			assertTrue(message, message.contains("user.get"));
			assertTrue("both interfaces must be named: " + message, message.contains(UserApi.class.getName()));
			assertTrue("both interfaces must be named: " + message,
				message.contains(ShadowingUserApi.class.getName()));
		}
	}

	@Test
	public void aBrokenServiceFailsAtBuildBeforeTheDispatcherExists() {
		try {
			JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService(UserApi.class, new UserApiImpl())
				.withService(BrokenOrderApi.class, new BrokenOrderApiImpl())
				.build();
			fail("a broken interface must not produce a dispatcher");
		} catch (JsonRpcContractException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(BrokenOrderApi.class.getName()));
		}
	}

	@Test
	public void registeringTheSameInterfaceTwiceIsRefused() {
		try {
			JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService(UserApi.class, new UserApiImpl())
				.withService(UserApi.class, new UserApiImpl())
				.build();
			fail("registering one interface twice is ambiguous, not additive");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(UserApi.class.getName()));
		}
	}

	@Test
	public void anImplementationThatDoesNotImplementTheInterfaceIsRefused() {
		try {
			//noinspection unchecked,rawtypes
			JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService((Class) OrderApi.class, new UserApiImpl())
				.build();
			fail("the implementation must be an instance of the service type");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(OrderApi.class.getName()));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Fixtures.
	// ---------------------------------------------------------------------------------------------------

	@JsonRpcService("order")
	public interface OrderApi {
		@JsonRpcMethod("get")
		Promise<String> get(@JsonRpcParam("id") long id);
	}

	public static final class OrderApiImpl implements OrderApi {
		@Override
		public Promise<String> get(long id) {
			return Promise.of("order-" + id);
		}
	}

	/** A second interface claiming {@code user.get} — the cross-interface counterpart of FR-025. */
	@JsonRpcService("user")
	public interface ShadowingUserApi {
		@JsonRpcMethod("get")
		Promise<String> get(@JsonRpcParam("id") long id);
	}

	public static final class ShadowingUserApiImpl implements ShadowingUserApi {
		@Override
		public Promise<String> get(long id) {
			return Promise.of("shadow-" + id);
		}
	}

	@JsonRpcService("broken")
	public interface BrokenOrderApi {
		@JsonRpcMethod("get")
		void get(@JsonRpcParam("id") long id);
	}

	public static final class BrokenOrderApiImpl implements BrokenOrderApi {
		@Override
		public void get(long id) {}
	}

	private static String dispatch(JsonRpcDispatcher dispatcher, String document) {
		return new String(await(dispatcher.dispatch(document.getBytes(UTF_8))), UTF_8);
	}
}
