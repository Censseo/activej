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

package io.activej.jsonrpc.transport.ws.fixtures;

import io.activej.promise.Promise;

import java.util.List;

/**
 * {@link WideApi}'s implementation. Deliberately trivial: the fixture exists so the OpenRPC document
 * is large, and {@code rpc.discover} is answered from the <b>descriptors</b> — no method here is ever
 * invoked by the discovery tests. The bodies are still real answers rather than {@code throw}s, so
 * the fixture stays usable if a later test wants to call one.
 */
public final class WideApiImpl implements WideApi {
	private static final Item ITEM = new Item(1L, "item", 1.0);
	private static final Summary SUMMARY = new Summary(0, 0.0, List.of());

	@Override
	public Promise<Item> alpha(long id, String name) {
		return Promise.of(new Item(id, name, 0.0));
	}

	@Override
	public Promise<Item> bravo(long id, double weight) {
		return Promise.of(new Item(id, "bravo", weight));
	}

	@Override
	public Promise<List<Item>> charlie(String prefix, int limit) {
		return Promise.of(List.of(ITEM));
	}

	@Override
	public Promise<String> delta(String left, String right) {
		return Promise.of(left + right);
	}

	@Override
	public Promise<String> echo(String text) {
		return Promise.of(text);
	}

	@Override
	public Promise<Boolean> foxtrot(boolean flag) {
		return Promise.of(flag);
	}

	@Override
	public Promise<Integer> golf(int a, int b, int c) {
		return Promise.of(a + b + c);
	}

	@Override
	public Promise<Item> hotel() {
		return Promise.of(ITEM);
	}

	@Override
	public Promise<List<String>> india(int count) {
		return Promise.of(List.of());
	}

	@Override
	public Promise<Summary> juliett(Item item) {
		return Promise.of(SUMMARY);
	}

	@Override
	public Promise<Summary> kilo(List<Item> items) {
		return Promise.of(SUMMARY);
	}

	@Override
	public Promise<Double> lima(double value) {
		return Promise.of(value);
	}

	@Override
	public Promise<Long> mike(long value) {
		return Promise.of(value);
	}

	@Override
	public Promise<Void> november(String token) {
		return Promise.complete();
	}

	@Override
	public Promise<Summary> oscar(Item first, Item second) {
		return Promise.of(SUMMARY);
	}

	@Override
	public Promise<String> papa() {
		return Promise.of("papa");
	}

	@Override
	public void quebec(long id) {}

	@Override
	public void romeo(long id, String reason) {}

	@Override
	public void sierra() {}

	@Override
	public void tango(Item item) {}

	@Override
	public Promise<Item> uniform(long id) {
		return Promise.of(new Item(id, "uniform", 0.0));
	}

	@Override
	public Promise<List<Summary>> victor(List<Long> ids) {
		return Promise.of(List.of());
	}

	@Override
	public Promise<Summary> whiskey(String name, double weight, boolean flag) {
		return Promise.of(SUMMARY);
	}

	@Override
	public Promise<String> xray(String a, String b, String c, String d) {
		return Promise.of(a + b + c + d);
	}
}
