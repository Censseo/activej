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

package io.activej.jsonrpc.service.impl;

import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import io.activej.common.annotation.ExposedInternals;
import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodec;
import io.activej.json.JsonValidationException;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.service.JsonRpcMethodDescriptor;
import io.activej.jsonrpc.service.JsonRpcParamDescriptor;

import java.io.IOException;
import java.util.List;

/**
 * Decodes one inbound {@code params} member into the argument array of one method, in either calling
 * convention (FR-042, FR-043, FR-044).
 *
 * <h2>One codec, both styles</h2>
 * JSON-RPC 2.0 lets a peer choose the style per call, so the style is read from the <b>first token</b> of the
 * value rather than configured: {@code [} is positional, <code>{</code> is named. A server therefore always
 * accepts both (FR-088) and {@code JsonRpcParamStyle} never configures it.
 *
 * <h2>Every refusal is one exception, carrying nothing (FR-045)</h2>
 * Arity mismatch, a missing or unknown named key, named {@code params} sent to a positional-only method, and
 * a value the declared codec rejects all surface as a {@link MalformedDataException}, which the dispatcher
 * turns into a bare {@code -32602}. The messages here are for a developer reading a stack trace, never for
 * the wire: <b>nothing</b> from this class reaches an outgoing document, because a decode failure's message
 * embeds the offending input by construction.
 *
 * <h2>Not part of the supported API surface</h2>
 * {@code io.activej.jsonrpc.service.impl} is the dispatcher's and the proxy's own machinery.
 */
@ExposedInternals
public final class ParamsCodec implements JsonCodec<Object[]> {
	private static final Object[] NO_ARGS = new Object[0];

	private final JsonRpcMethodDescriptor descriptor;

	/**
	 * @param descriptor the method whose parameters this codec reads; already validated, so every parameter
	 *                   carries a resolved codec
	 */
	public ParamsCodec(JsonRpcMethodDescriptor descriptor) {
		this.descriptor = descriptor;
	}

	/**
	 * Decodes {@code params} into the argument array of {@code descriptor}'s method.
	 *
	 * @param descriptor the method the arguments are for
	 * @param params     the raw {@code params} member, possibly {@link JsonRpcPayload#absent()}
	 * @return an argument array of exactly the method's arity; empty for a zero-argument method
	 * @throws MalformedDataException on any mismatch — the caller answers {@code -32602} and discards this
	 *                                exception without copying anything out of it
	 */
	public static Object[] decode(JsonRpcMethodDescriptor descriptor, JsonRpcPayload params)
		throws MalformedDataException {
		if (params.isAbsent()) {
			// FR-044: an omitted params member is exactly right for a zero-arity method and wrong for any other
			if (descriptor.params().isEmpty()) return NO_ARGS;
			throw new MalformedDataException("params is absent but the method takes arguments");
		}
		return params.decode(new ParamsCodec(descriptor));
	}

	@Override
	public Object[] read(JsonReader<?> reader) throws IOException {
		List<JsonRpcParamDescriptor> params = descriptor.params();

		// "params":null is read exactly as an omitted member: §4.2 permits the literal, and treating it as a
		// third case would give one wire fact two answers
		if (reader.wasNull()) {
			if (params.isEmpty()) return NO_ARGS;
			throw new JsonValidationException("params is null but the method takes arguments");
		}

		byte token = reader.last();
		if (token == JsonWriter.ARRAY_START) return readPositional(reader, params);
		if (token == JsonWriter.OBJECT_START) return readNamed(reader, params);
		throw new JsonValidationException("params must be an array or an object");
	}

	/** Encoding is the client proxy's direction and is not implemented by this decoder-side codec. */
	@Override
	public void write(JsonWriter writer, Object[] value) {
		throw new UnsupportedOperationException(
			"ParamsCodec only decodes; the client proxy renders its own params member");
	}

	private static Object[] readPositional(JsonReader<?> reader, List<JsonRpcParamDescriptor> params)
		throws IOException {
		int arity = params.size();
		Object[] args = new Object[arity];
		int i = 0;

		if (reader.getNextToken() != JsonWriter.ARRAY_END) {
			while (true) {
				if (i == arity) {
					throw new JsonValidationException("params array is longer than the method's arity");
				}
				args[i] = params.get(i).codec().read(reader);
				i++;
				if (reader.getNextToken() != JsonWriter.COMMA) break;
				reader.getNextToken();
			}
			reader.checkArrayEnd();
		}

		if (i != arity) throw new JsonValidationException("params array is shorter than the method's arity");
		return args;
	}

	private static Object[] readNamed(JsonReader<?> reader, List<JsonRpcParamDescriptor> params)
		throws IOException {
		int arity = params.size();
		// FR-043: a method with any unannotated parameter has no key to match against, so named params are
		// refused before a single value is read rather than half-decoded and then abandoned
		for (JsonRpcParamDescriptor param : params) {
			if (param.name() == null) {
				throw new JsonValidationException("the method is positional-only: parameter " + param.index() +
												 " carries no @JsonRpcParam name");
			}
		}

		Object[] args = new Object[arity];
		boolean[] present = new boolean[arity];

		if (reader.getNextToken() != JsonWriter.OBJECT_END) {
			while (true) {
				String key = reader.readKey();
				int index = indexOf(params, key);
				if (index < 0) throw new JsonValidationException("no parameter is named by this key");
				if (present[index]) throw new JsonValidationException("a parameter key appears twice");
				args[index] = params.get(index).codec().read(reader);
				present[index] = true;
				if (reader.getNextToken() != JsonWriter.COMMA) break;
				reader.getNextToken();
			}
			reader.checkObjectEnd();
		}

		for (int i = 0; i < arity; i++) {
			if (!present[i]) throw new JsonValidationException("a parameter has no key in params");
		}
		return args;
	}

	private static int indexOf(List<JsonRpcParamDescriptor> params, String key) {
		for (int i = 0; i < params.size(); i++) {
			if (key.equals(params.get(i).name())) return i;
		}
		return -1;
	}
}
