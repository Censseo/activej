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

package io.activej.jsonrpc.transport.http;

import io.activej.common.annotation.ExposedInternals;
import io.activej.http.MediaType;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * The accepted request media types of a JSON-RPC-over-HTTP endpoint (FR-016): the three JSON media
 * types — {@code application/json} and the historical JSON-RPC aliases
 * {@code application/json-rpc} and {@code application/jsonrequest} — as one allow-list, with
 * parameters ignored when matching.
 * <p>
 * Matching is done on the <b>raw header string</b> (probe R4): {@code HttpRequest} exposes no
 * parsed {@code ContentType}, and {@code core-http}'s parameter-aware decoder is package-private,
 * so {@code application/json; charset=UTF-8} arrives verbatim and the matcher strips everything
 * from the first {@code ;} and compares case-insensitively itself (RFC 2045 §2 — media types are
 * case-insensitive). An absent header is rejected rather than assumed — a deliberate strictness
 * decision (FR-016).
 * <p>
 * {@link #RAW_ACCEPTED} is the single source of truth: the matching loop (zero allocation,
 * {@code regionMatches} against it) and the introspectable {@link #ACCEPTED} set share it rather
 * than carrying two copies of the three strings that could drift.
 */
@ExposedInternals
public final class JsonRpcHttpMediaTypes {
	private JsonRpcHttpMediaTypes() {}

	/** The three accepted media types, one source of truth. FR-016. Immutable (NIT-1). */
	public static final List<String> RAW_ACCEPTED = List.of(
		"application/json", "application/json-rpc", "application/jsonrequest");

	/** An interning view of the same three values, for introspection and tests. */
	public static final Set<MediaType> ACCEPTED = Set.of(
		MediaType.of("application/json"),
		MediaType.of("application/json-rpc"),
		MediaType.of("application/jsonrequest"));

	/**
	 * Media type compared, parameters ignored, absent header rejected. Matches the raw header
	 * string: strip everything from the first {@code ;}, trim surrounding whitespace, and compare
	 * case-insensitively (RFC 2045 §2).
	 */
	public static boolean isAccepted(@Nullable String contentTypeHeader) {
		if (contentTypeHeader == null) return false;
		int end = contentTypeHeader.indexOf(';');
		if (end < 0) end = contentTypeHeader.length();
		int start = 0;
		while (start < end && contentTypeHeader.charAt(start) <= ' ') start++;
		while (end > start && contentTypeHeader.charAt(end - 1) <= ' ') end--;
		for (String accepted : RAW_ACCEPTED) {
			if (accepted.length() == end - start
				&& contentTypeHeader.regionMatches(true, start, accepted, 0, accepted.length())) {
				return true;
			}
		}
		return false;
	}
}
