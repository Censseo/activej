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

import io.activej.bytebuf.ByteBuf;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.OTHER_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T075 / FR-048: several requests issued to one authority before any connection exists must share a
 * <b>single</b> in-flight connect promise.
 * <p>
 * The fabric makes this a real race rather than a simulated one: {@code Http3Client} is handed every
 * request synchronously, in one reactor tick, long before the first handshake datagram is even
 * delivered — which is precisely the window in which a per-request connect would open several
 * connections. {@code Http3Server.connectionsAccepted()} is the count that would show it.
 */
public final class Http3ClientConnectRaceTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int CONCURRENT_REQUESTS = 8;

	private ManualEventloop loop;
	private @Nullable Http3ClientFixture fixture;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> HttpResponse.ok200()
				.withBody(request.getPath().getBytes(UTF_8))
				.toPromise())
			.start();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void concurrentRequestsToOneAuthorityOpenExactlyOneConnection() {
		List<Promise<HttpResponse>> requests = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			requests.add(fixture.client().request(HttpRequest.get(url(HOST, "/r" + i)).build()));
		}
		// Issued before a single datagram crossed: nothing can have connected yet, so every one of them
		// is racing for the same authority.
		assertEquals(0, fixture.server().connectionsAccepted());

		fixture.await(Promises.all(requests));

		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			Promise<HttpResponse> request = requests.get(i);
			assertTrue("request " + i + " succeeded: " + request, request.isResult());
			assertEquals("/r" + i, body(request.getResult()));
		}
		assertEquals("FR-048: one in-flight connect promise, one connection", 1, fixture.server().connectionsAccepted());
		assertEquals(1, fixture.client().connectionCount());
		assertEquals("the shared connect resolved the host once, not once per request",
			1, fixture.dns().resolved().size());
	}

	@Test
	public void aConcurrentRaceToTwoAuthoritiesOpensExactlyTwoConnections() {
		List<Promise<HttpResponse>> requests = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			String host = (i % 2 == 0) ? HOST : OTHER_HOST;
			requests.add(fixture.client().request(HttpRequest.get(url(host, "/r" + i)).build()));
		}

		fixture.await(Promises.all(requests));

		for (Promise<HttpResponse> request : requests) {
			assertTrue("the request succeeded: " + request, request.isResult());
			body(request.getResult());
		}
		assertEquals("one connect per authority, however many requests race", 2, fixture.server().connectionsAccepted());
		assertEquals(2, fixture.client().connectionCount());
	}

	@Test
	public void aFailedSharedConnectFailsEveryRacerAndLeavesNothingPooled() {
		fixture.dns().fail(OTHER_HOST);

		List<Promise<HttpResponse>> requests = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			requests.add(fixture.client().request(HttpRequest.get(url(OTHER_HOST, "/r" + i)).build()));
		}
		fixture.wire().driveUntil(() -> requests.stream().allMatch(Promise::isComplete));

		for (Promise<HttpResponse> request : requests) {
			assertTrue("every racer sees the shared connect's failure: " + request, request.isException());
		}
		assertEquals("a failed connect pools nothing", 0, fixture.client().connectionCount());
	}

	private String body(HttpResponse response) {
		ByteBuf body = fixture.await(response.loadBody());
		try {
			return body.getString(UTF_8);
		} finally {
			body.recycle();
		}
	}
}
