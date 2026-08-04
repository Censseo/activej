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

import io.activej.http.HttpMessages;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import org.junit.Test;

import java.util.List;

import static io.activej.http3.Http3Headers.Field;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A trailing HEADERS field list rides on {@link io.activej.http.HttpMessage}'s existing
 * attachment mechanism and round-trips (FR-038).
 */
public class Http3TrailersTest {

	@Test
	public void trailersRoundTripThroughARequest() {
		HttpRequest request = HttpMessages.requestBuilder(HttpVersion.HTTP_3_0, HttpMethod.POST, "https://example.com/").build();
		List<Field> trailers = List.of(new Field("x-checksum", "abc123"));

		assertNull(Http3Trailers.get(request));

		Http3Trailers.set(request, trailers);

		assertEquals(trailers, Http3Trailers.get(request));
	}

	@Test
	public void trailersRoundTripThroughAResponse() {
		HttpResponse response = HttpMessages.responseBuilder(HttpVersion.HTTP_3_0, 200).build();
		List<Field> trailers = List.of(new Field("x-checksum", "def456"), new Field("x-trace-id", "42"));

		Http3Trailers.set(response, trailers);

		assertEquals(trailers, Http3Trailers.get(response));
	}

	@Test
	public void settingTrailersTwiceOverwritesThePreviousValue() {
		HttpResponse response = HttpMessages.responseBuilder(HttpVersion.HTTP_3_0, 200).build();

		Http3Trailers.set(response, List.of(new Field("a", "1")));
		Http3Trailers.set(response, List.of(new Field("b", "2")));

		assertEquals(List.of(new Field("b", "2")), Http3Trailers.get(response));
	}
}
