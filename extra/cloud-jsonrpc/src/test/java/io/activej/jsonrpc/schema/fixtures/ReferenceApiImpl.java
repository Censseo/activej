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

package io.activej.jsonrpc.schema.fixtures;

import io.activej.promise.Promise;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A trivially total implementation of {@link ReferenceApi}.
 * <p>
 * The schema export reads the <b>interface</b> and never touches an implementation (FR-023, and
 * {@code JsonRpcServiceContract} invokes nothing), so this class exists only so the fixture can also be
 * registered on a real {@code JsonRpcDispatcher} — which needs an instance — and dispatched against.
 * <p>
 * Every answer is a <b>fixed</b> value derived from the arguments: nothing here reads a clock, a random
 * source or the environment, so a test that dispatches a call and freezes the response bytes stays
 * reproducible. {@link #AUDIT_ID} is a constant for exactly that reason.
 */
public final class ReferenceApiImpl implements ReferenceApi {
	/** A fixed UUID, so {@code reference.audit} answers the same bytes on every run. */
	public static final UUID AUDIT_ID = UUID.fromString("0eb4c7a2-6ad0-4a1e-9f5f-2b0d3a4e5c60");

	private final List<String> invocations = new ArrayList<>();

	@Override
	public Promise<Order> createOrder(String customer, int quantity, boolean express) {
		invocations.add("createOrder(" + customer + ", " + quantity + ", " + express + ')');
		return Promise.of(new Order(1L, customer, quantity, express, Status.NEW));
	}

	@Override
	public Promise<Double> sum(double left, double right) {
		invocations.add("sum(" + left + ", " + right + ')');
		return Promise.of(left + right);
	}

	@Override
	public Promise<Status> scalars(
		boolean flag, byte tiny, short small, int count, long id, float ratio, double amount, String label,
		char initial
	) {
		invocations.add("scalars(" + flag + ", " + tiny + ", " + small + ", " + count + ", " + id + ", " +
						ratio + ", " + amount + ", " + label + ", " + initial + ')');
		return Promise.of(flag ? Status.PAID : Status.CANCELLED);
	}

	@Override
	public Promise<BigDecimal> boxedScalars(
		Boolean flag, Byte tiny, Short small, Integer count, Long id, Float ratio, Double amount,
		Character initial, BigDecimal precise, BigInteger huge
	) {
		invocations.add("boxedScalars(" + flag + ", " + tiny + ", " + small + ", " + count + ", " + id + ", " +
						ratio + ", " + amount + ", " + initial + ", " + precise + ", " + huge + ')');
		return Promise.of(precise);
	}

	@Override
	public Promise<Inventory> containers(
		String[] tags, List<OrderLine> lines, Set<Status> statuses, Map<String, Integer> quantities
	) {
		invocations.add("containers(" + tags.length + " tags, " + lines.size() + " lines, " +
						statuses.size() + " statuses, " + quantities.size() + " quantities)");
		return Promise.of(new Inventory(tags, lines, statuses, quantities));
	}

	@Override
	public Promise<List<Status>> catalogue() {
		invocations.add("catalogue()");
		return Promise.of(List.of(Status.values()));
	}

	@Override
	public Promise<Void> archive(long id) {
		invocations.add("archive(" + id + ')');
		return Promise.complete();
	}

	@Override
	public Promise<UUID> audit(Instant at, Map<Status, BigDecimal> byStatus) {
		invocations.add("audit(" + at + ", " + byStatus.size() + " entries)");
		return Promise.of(AUDIT_ID);
	}

	@Override
	public void orderSeen(long id) {
		invocations.add("orderSeen(" + id + ')');
	}

	@Override
	public Promise<Void> ping(String note) {
		invocations.add("ping(" + note + ')');
		return Promise.complete();
	}

	/** Every invocation so far, in order, rendered as {@code name(args)} — as {@code UserApiImpl} does. */
	public List<String> invocations() {
		return invocations;
	}
}
