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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class HttpVersionTest {
	@Test
	public void http3_0Exists() {
		assertEquals(HttpVersion.HTTP_3_0, HttpVersion.valueOf("HTTP_3_0"));
	}

	@Test
	public void httpMessageReportsHttp3_0() {
		HttpRequest request = HttpMessages.requestBuilder(HttpVersion.HTTP_3_0, HttpMethod.GET, "https://example.com/").build();
		assertSame(HttpVersion.HTTP_3_0, request.getVersion());
		request.recycle();

		HttpResponse response = HttpMessages.responseBuilder(HttpVersion.HTTP_3_0, 200).build();
		assertSame(HttpVersion.HTTP_3_0, response.getVersion());
		response.recycle();
	}
}
