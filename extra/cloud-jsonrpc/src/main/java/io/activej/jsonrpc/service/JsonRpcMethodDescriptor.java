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

import io.activej.json.JsonCodec;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Everything the dispatcher and the client proxy need about one annotated method, resolved once during
 * {@link JsonRpcServiceContract} construction and never recomputed (FR-020, FR-033).
 *
 * <h2>Deliberately not a {@code record}</h2>
 * {@link #isNamable()} is <b>derived</b> from {@link #params()} and cached, and a record would publish a
 * canonical constructor — an invitation to build a descriptor outside the validating path, where none of the
 * invariants below hold. The type is therefore a final class with a package-private constructor: the only
 * way to obtain one is through a contract that has already validated it.
 *
 * <h2>Invariants</h2>
 * <ul>
 *     <li>{@link #isNotification()} ⟹ {@link #resultCodec()} is {@code null} and
 *     {@link #isSynchronousResult()} is {@code false} — anything else was rejected before a descriptor
 *     existed (FR-026).</li>
 *     <li>{@link #resultCodec()} {@code == null} on a non-notification ⟹ the declared result is
 *     {@code Promise<Void>}, which renders as the JSON literal {@code null} with no codec at all
 *     (FR-030).</li>
 *     <li>{@link #params()} empty ⟹ a call omits {@code params} entirely (FR-089a).</li>
 * </ul>
 */
public final class JsonRpcMethodDescriptor {
	private final String wireName;
	private final Method method;
	private final boolean notification;
	private final List<JsonRpcParamDescriptor> params;
	private final @Nullable JsonCodec<?> resultCodec;
	private final boolean synchronousResult;
	private final boolean namable;

	JsonRpcMethodDescriptor(
		String wireName, Method method, boolean notification, List<JsonRpcParamDescriptor> params,
		@Nullable JsonCodec<?> resultCodec, boolean synchronousResult
	) {
		this.wireName = Objects.requireNonNull(wireName, "wireName");
		this.method = Objects.requireNonNull(method, "method");
		this.notification = notification;
		this.params = List.copyOf(params);
		this.resultCodec = resultCodec;
		this.synchronousResult = synchronousResult;
		this.namable = this.params.stream().allMatch(param -> param.name() != null);
	}

	/** The effective wire name, with {@link JsonRpcService}'s prefix already applied (FR-015). */
	public String wireName() {
		return wireName;
	}

	/** The reflective handle — the interface's method, not an implementation's override. */
	public Method method() {
		return method;
	}

	/** Whether this method is a {@link JsonRpcNotification}, and so never produces a response element. */
	public boolean isNotification() {
		return notification;
	}

	/** The parameters, in declaration order. Unmodifiable, possibly empty, never {@code null}. */
	public List<JsonRpcParamDescriptor> params() {
		return params;
	}

	/**
	 * The codec for the result value, or {@code null} <b>iff</b> the declared result is {@code void} or
	 * {@code Promise<Void>} — in which case the wire value is the JSON literal {@code null} (FR-030).
	 */
	public @Nullable JsonCodec<?> resultCodec() {
		return resultCodec;
	}

	/**
	 * Whether the interface declares a bare {@code T} rather than a {@code Promise<T>}. A dispatcher wraps
	 * such a return into a completed promise (FR-046); a client proxy refuses the method outright, since it
	 * could only answer it by blocking the reactor (FR-062).
	 * <p>
	 * Always {@code false} for a notification.
	 */
	public boolean isSynchronousResult() {
		return synchronousResult;
	}

	/**
	 * Whether every parameter carries a {@link JsonRpcParam} name, and so whether this method can be called
	 * with named {@code params}. Vacuously {@code true} for a zero-argument method.
	 * <p>
	 * Derived at construction rather than per call: the server asks it for every inbound named-{@code params}
	 * document (FR-043) and the client asks it once per method at {@code proxy(...)} time (FR-090).
	 */
	public boolean isNamable() {
		return namable;
	}

	@Override
	public String toString() {
		return "JsonRpcMethodDescriptor[" + wireName + " -> " +
			   method.getDeclaringClass().getName() + '.' + method.getName() +
			   (notification ? ", notification" : "") + ']';
	}
}
