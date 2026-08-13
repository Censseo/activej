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

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * One parameter of a {@link JsonRpcMethodDescriptor}: where it sits, what it is declared as, how it is
 * encoded, and what a named {@code params} object calls it.
 *
 * <h2>The codec is never null</h2>
 * A type {@code JsonCodecFactory} cannot resolve is a <b>contract violation reported before any descriptor
 * exists</b> (FR-029), never a descriptor carrying a {@code null} codec. Nothing downstream therefore has to
 * ask whether a parameter is encodable.
 *
 * @param index the 0-based declaration position — also the position in a positional {@code params} array
 * @param type  the declared type with every type variable already bound (see
 *              {@code Types.getAllTypeBindings} / {@code Types.bind}); never a {@code TypeVariable}
 * @param codec the codec resolved once, at contract construction; never {@code null}
 * @param name  the {@link JsonRpcParam} value, or {@code null} when the parameter carries no annotation —
 *              which makes the whole method positional-only (FR-043)
 */
public record JsonRpcParamDescriptor(int index, Type type, JsonCodec<?> codec, @Nullable String name) {
	/**
	 * @throws IllegalArgumentException if {@code index} is negative
	 * @throws NullPointerException     if {@code type} or {@code codec} is {@code null}
	 */
	public JsonRpcParamDescriptor {
		if (index < 0) throw new IllegalArgumentException("index must not be negative: " + index);
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(codec, "codec must be resolved before a descriptor is built");
	}
}
