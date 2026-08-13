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

import io.activej.jsonrpc.service.fixtures.BrokenApis;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * User story 3, scenario 5 — "nothing was listening": a broken contract fails
 * {@link JsonRpcDispatcher.Builder#build()} itself, before a transport could ever have been constructed to
 * serve it.
 */
public class JsonRpcStartupOrderTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@Test
	public void aBrokenContractFailsBeforeAnyTransportExists() {
		AtomicBoolean transportConstructed = new AtomicBoolean(false);

		try {
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService(BrokenApis.UnannotatedAbstractMethod.class,
					stub(BrokenApis.UnannotatedAbstractMethod.class))
				.build();
			// only reached if build() succeeded — a transport would be wired to `dispatcher` here in real use
			transportConstructed.set(true);
			fail("expected build() to throw JsonRpcContractException for " + dispatcher);
		} catch (JsonRpcContractException expected) {
			// the contract is validated, and only then would a transport be built
		}

		assertFalse("a transport must never be constructed when the contract fails to build",
			transportConstructed.get());
	}

	/**
	 * The same guarantee as {@link #aBrokenContractFailsBeforeAnyTransportExists()}, for a different rule
	 * violation: an unresolvable parameter type (rule 8, FR-029) blocks {@code build()} exactly as an
	 * unannotated abstract method does — "any contract violation blocks all I/O", not just this one kind.
	 */
	@Test
	public void anUnresolvableParameterTypeAlsoFailsBeforeAnyTransportExists() {
		AtomicBoolean transportConstructed = new AtomicBoolean(false);

		try {
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
				.withService(BrokenApis.UnresolvableParameterType.class,
					stub(BrokenApis.UnresolvableParameterType.class))
				.build();
			// only reached if build() succeeded — a transport would be wired to `dispatcher` here in real use
			transportConstructed.set(true);
			fail("expected build() to throw JsonRpcContractException for " + dispatcher);
		} catch (JsonRpcContractException expected) {
			// the contract is validated, and only then would a transport be built
		}

		assertFalse("a transport must never be constructed when the contract fails to build",
			transportConstructed.get());
	}

	/** A do-nothing implementation — {@code build()} must fail before any method of it is ever called. */
	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> serviceType) {
		return (T) Proxy.newProxyInstance(serviceType.getClassLoader(), new Class<?>[] {serviceType},
			(proxy, method, args) -> {
				throw new AssertionError("must not be invoked: " + method);
			});
	}
}
