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

package io.activej.http;

import io.activej.common.annotation.ExposedInternals;

/**
 * Entry points for an alternative HTTP transport (currently {@code io.activej.http3}) to construct
 * {@link HttpRequest}/{@link HttpResponse} with an explicit {@link HttpVersion}.
 * <p>
 * The version-carrying constructors of both message types are package-private, and every public
 * factory ({@link HttpRequest#builder}, {@link HttpResponse#builder}) hardcodes
 * {@link HttpVersion#HTTP_1_1} — so a message reporting any other version is otherwise
 * unconstructible from outside this package. This class is that seam.
 * <p>
 * Not part of the public API surface — application code builds messages through
 * {@link HttpRequest#builder} / {@link HttpResponse#ofCode} and gets HTTP/1.1.
 */
@ExposedInternals
public final class HttpMessages {
	private HttpMessages() {}

	/** A request builder reporting {@code version}, with no owning connection. */
	public static HttpRequest.Builder requestBuilder(HttpVersion version, HttpMethod method, String url) {
		return new HttpRequest(version, method, UrlParser.of(url), null).new Builder();
	}

	/** A response builder reporting {@code version}, with no owning connection. */
	public static HttpResponse.Builder responseBuilder(HttpVersion version, int code) {
		return new HttpResponse(version, code, null).new Builder();
	}
}
