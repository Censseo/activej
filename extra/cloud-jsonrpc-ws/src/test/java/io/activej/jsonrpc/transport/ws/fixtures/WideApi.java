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

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

import java.util.List;

/**
 * A deliberately <b>wide</b> service whose only purpose is to make the OpenRPC document big
 * (feature 018 adversarial, US2). Every other fixture in this package describes one or two methods,
 * which produces a discovery document of a few hundred bytes — small enough that every size-tier
 * mechanic in this module (the {@code 1009} accumulation bound, the frame/message split, TCP
 * segmentation) is trivially satisfied and therefore untested against discovery.
 * <p>
 * The interesting property of {@code rpc.discover} is precisely that its answer is the <b>largest
 * document a deployment ever emits</b>, that it <b>grows with the service surface</b> — one Method
 * Object per wire name, one Content Descriptor per parameter, one JSON Schema per type — and that
 * nothing in the JSON-RPC line bounds that growth. A deployment adds services over its lifetime; the
 * discovery answer is the one document whose size is a function of the deployment rather than of the
 * call.
 * <p>
 * Twenty-four wire names across four shapes, so the document is several kilobytes rather than several
 * hundred bytes, and so no single mapping rule dominates it:
 * <ul>
 *     <li>namable methods with annotated parameters (a {@code params} object per method);</li>
 *     <li>notifications, which emit a Method Object with <b>no</b> {@code result} (rule M3);</li>
 *     <li>{@code record} results and {@code List} results, whose schemas are derived;</li>
 *     <li>zero-parameter methods, whose {@code params} array is empty.</li>
 * </ul>
 * The exact size is never hard-coded anywhere: the tests read
 * {@code JsonRpcDispatcher.discoveryDocument().length} and derive their caps from it, so adding a
 * method here can never silently invalidate a bound.
 */
@JsonRpcService("wide")
public interface WideApi {
	@JsonRpcMethod("alpha")
	Promise<Item> alpha(@JsonRpcParam("id") long id, @JsonRpcParam("name") String name);

	@JsonRpcMethod("bravo")
	Promise<Item> bravo(@JsonRpcParam("id") long id, @JsonRpcParam("weight") double weight);

	@JsonRpcMethod("charlie")
	Promise<List<Item>> charlie(@JsonRpcParam("prefix") String prefix, @JsonRpcParam("limit") int limit);

	@JsonRpcMethod("delta")
	Promise<String> delta(@JsonRpcParam("left") String left, @JsonRpcParam("right") String right);

	@JsonRpcMethod("echo")
	Promise<String> echo(@JsonRpcParam("text") String text);

	@JsonRpcMethod("foxtrot")
	Promise<Boolean> foxtrot(@JsonRpcParam("flag") boolean flag);

	@JsonRpcMethod("golf")
	Promise<Integer> golf(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b, @JsonRpcParam("c") int c);

	@JsonRpcMethod("hotel")
	Promise<Item> hotel();

	@JsonRpcMethod("india")
	Promise<List<String>> india(@JsonRpcParam("count") int count);

	@JsonRpcMethod("juliett")
	Promise<Summary> juliett(@JsonRpcParam("item") Item item);

	@JsonRpcMethod("kilo")
	Promise<Summary> kilo(@JsonRpcParam("items") List<Item> items);

	@JsonRpcMethod("lima")
	Promise<Double> lima(@JsonRpcParam("value") double value);

	@JsonRpcMethod("mike")
	Promise<Long> mike(@JsonRpcParam("value") long value);

	@JsonRpcMethod("november")
	Promise<Void> november(@JsonRpcParam("token") String token);

	@JsonRpcMethod("oscar")
	Promise<Summary> oscar(@JsonRpcParam("first") Item first, @JsonRpcParam("second") Item second);

	@JsonRpcMethod("papa")
	Promise<String> papa();

	@JsonRpcNotification("quebec")
	void quebec(@JsonRpcParam("id") long id);

	@JsonRpcNotification("romeo")
	void romeo(@JsonRpcParam("id") long id, @JsonRpcParam("reason") String reason);

	@JsonRpcNotification("sierra")
	void sierra();

	@JsonRpcNotification("tango")
	void tango(@JsonRpcParam("item") Item item);

	@JsonRpcMethod("uniform")
	Promise<Item> uniform(@JsonRpcParam("id") long id);

	@JsonRpcMethod("victor")
	Promise<List<Summary>> victor(@JsonRpcParam("ids") List<Long> ids);

	@JsonRpcMethod("whiskey")
	Promise<Summary> whiskey(@JsonRpcParam("name") String name, @JsonRpcParam("weight") double weight,
		@JsonRpcParam("flag") boolean flag);

	@JsonRpcMethod("xray")
	Promise<String> xray(@JsonRpcParam("a") String a, @JsonRpcParam("b") String b, @JsonRpcParam("c") String c,
		@JsonRpcParam("d") String d);

	/** A derived-codec record result; its component names are the JSON keys (DI-6/DI-7). */
	record Item(long id, String name, double weight) {}

	/** A second derived record, so the document carries more than one object schema. */
	record Summary(int count, double total, List<String> names) {}
}
