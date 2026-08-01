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

package io.activej.http3;

import io.activej.http.HttpMessage;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The optional trailing HEADERS section of an HTTP/3 message (RFC 9114 §4.1), carried on the
 * existing {@link HttpMessage#attach(String, Object)} / {@link HttpMessage#getAttachment(String)}
 * mechanism rather than a first-class {@code core-http} trailers API — {@code core-http} has none,
 * and trailers are still an open scope question for the HTTP domain (FR-038, contracts/core-http-
 * delta.md §4).
 * <p>
 * A pure attachment accessor: it does not itself validate the field list. Validation of a
 * trailer section (no pseudo-headers, no connection-specific field, …) is
 * {@link Http3Headers#validateTrailers(List)}.
 */
public final class Http3Trailers {
	private Http3Trailers() {
	}

	/** The {@link HttpMessage#attach(String, Object)} key trailers are stored under. */
	public static final String ATTACHMENT_KEY = "io.activej.http3.trailers";

	/** Attaches {@code trailers} to {@code message}, overwriting any previously attached value. */
	public static void set(HttpMessage message, List<Http3Headers.Field> trailers) {
		message.attach(ATTACHMENT_KEY, trailers);
	}

	/** The trailers attached to {@code message} by {@link #set}, or {@code null} if none were. */
	public static @Nullable List<Http3Headers.Field> get(HttpMessage message) {
		return message.getAttachment(ATTACHMENT_KEY);
	}
}
