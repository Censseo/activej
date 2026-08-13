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

import java.util.List;

/**
 * Every fault found in one service interface, reported together (FR-031, FR-032).
 * <p>
 * Extends {@link IllegalArgumentException} because a broken interface is caller misuse discovered at
 * construction — the same category as builder misuse — and because a consumer must be able to tell "your
 * interface is wrong" apart from any other construction failure by catching this type specifically.
 *
 * <h2>Why every violation, not the first</h2>
 * Introspection is total: it walks the whole interface before it decides. Reporting only the first fault
 * turns fixing an interface into a compile-run-read loop, once per fault. The message renders all of them,
 * and {@link #violations()} exposes the list so a test can assert on content without parsing prose.
 */
public class JsonRpcContractException extends IllegalArgumentException {
	private final List<String> violations;

	/**
	 * @param serviceType the interface under introspection, named in the message
	 * @param violations  every violation found, each already naming its declaring type and method; never
	 *                    empty
	 * @throws IllegalArgumentException if {@code violations} is empty — an exception with nothing to report
	 *                                  would say a valid interface is invalid
	 */
	public JsonRpcContractException(Class<?> serviceType, List<String> violations) {
		this(serviceType.getName(), violations);
	}

	/**
	 * The form for a fault that belongs to no single interface — a wire name claimed by two of them
	 * (FR-037).
	 *
	 * @param subject    what the violations are about, named in the message
	 * @param violations every violation found; never empty
	 */
	public JsonRpcContractException(String subject, List<String> violations) {
		super(render(subject, violations));
		this.violations = List.copyOf(violations);
	}

	/** Every violation, in discovery order. Unmodifiable, never empty. */
	public List<String> violations() {
		return violations;
	}

	private static String render(String subject, List<String> violations) {
		if (violations.isEmpty()) {
			throw new IllegalArgumentException("a contract exception must carry at least one violation");
		}
		StringBuilder sb = new StringBuilder()
			.append(violations.size() == 1 ? "1 violation" : violations.size() + " violations")
			.append(" in JSON-RPC service ")
			.append(subject)
			.append(':');
		for (String violation : violations) {
			sb.append("\n\t").append(violation);
		}
		return sb.toString();
	}
}
