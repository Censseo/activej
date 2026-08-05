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
import io.activej.bytebuf.ByteBufPool;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.tls.InMemoryQuicSessionCache;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * One 0-RTT-enabled {@link Http3Server}/{@link Http3Client} exchange, reporting the four numbers
 * {@code Http3ServerQuicSettingsWiringTest} reads — and nothing else, because it is also
 * <b>invoked reflectively from a second class loader</b>, where the only thing that can cross the
 * boundary is a {@code long[]}.
 * <p>
 * That second loader is what makes the assertion mean anything: {@code QuicConnectionSettings}'
 * {@code ApplicationSettings} constants resolve once at class-init, so the only way to observe a
 * configured {@code sessionTicketsPerHandshake} or {@code ticketAgeTolerance} is a fresh
 * {@code QuicConnectionSettings} class that first loads with the system property already set.
 */
public final class Http3TicketSettingsProbe {

	/** {@code {ticketsPerHandshake, ticketAgeToleranceMillis, sessionTicketsStored, leakedBuffers}}. */
	public static long[] run() {
		long created = ByteBufPool.getStats().getCreatedItems();
		long pooled = ByteBufPool.getStats().getPoolItems();

		Http3Settings zeroRttOn = Http3Settings.builder().withZeroRttEnabled(true).build();
		ManualEventloop loop = new ManualEventloop();
		long[] result;
		try (Http3ClientFixture fixture = new Http3ClientFixture(loop)
			.withServlet(request -> HttpResponse.ok200()
				.withBody(request.getPath().substring(1).getBytes(UTF_8))
				.toPromise())
			.withServerSettings(zeroRttOn)
			.withClientSettings(zeroRttOn)
			.withSessionCache(InMemoryQuicSessionCache.create(16, loop::currentTimeMillis))
			.start()
		) {
			HttpResponse response =
				fixture.await(fixture.client().request(HttpRequest.get(url(HOST, "/probe")).build()));
			ByteBuf body = fixture.await(response.loadBody());
			try {
				if (!"probe".equals(body.getString(UTF_8))) {
					throw new AssertionError("the probe exchange did not complete");
				}
			} finally {
				body.recycle();
			}
			result = new long[] {
				fixture.server().ticketsPerHandshake(),
				fixture.server().ticketAgeToleranceMillis(),
				fixture.client().sessionTicketsStored(),
				0};
		} finally {
			loop.tickUntilQuiet();
			loop.close();
		}
		result[3] = (ByteBufPool.getStats().getCreatedItems() - created)
					- (ByteBufPool.getStats().getPoolItems() - pooled);
		return result;
	}

	private Http3TicketSettingsProbe() {}
}
