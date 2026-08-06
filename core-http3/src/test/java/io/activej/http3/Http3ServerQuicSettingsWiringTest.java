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

import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * T155 (FR-045, FR-088b) — {@code -DQuicConnection.sessionTicketsPerHandshake} and
 * {@code -DQuicConnection.ticketAgeTolerance} reach the TLS layer through {@link Http3Server}.
 * <p>
 * They did not. {@code Http3Server} built its {@link io.activej.quic.tls.TlsServerConfig} without ever
 * calling the two matching {@code withXxx}, so {@code TlsServerConfig}'s own hardcoded 2 and 10 000 ms
 * always won — and because those coincide numerically with
 * {@link QuicConnectionSettings#DEFAULT_SESSION_TICKETS_PER_HANDSHAKE} and
 * {@link QuicConnectionSettings#DEFAULT_TICKET_AGE_TOLERANCE}, every default-valued assertion in the
 * suite passed either way. <b>Only a non-default value can tell the two apart</b>, which is what this
 * class exists to supply.
 * <p>
 * <b>Why a second class loader.</b> Those two settings have no builder call on {@code Http3Server} —
 * deliberately, see {@code Http3Server.quicSettings()} — so the public route is
 * {@code ApplicationSettings}, whose constants resolve once at class-init and are therefore already
 * fixed by the time any test method runs. A private {@link URLClassLoader} over the same class path
 * gives {@code QuicConnectionSettings} a fresh class-init with the properties in place; the probe runs
 * a whole real exchange inside it and hands back four numbers.
 * <p>
 * The default run is here too, in this loader and under {@link ByteBufRule} — not because asserting a
 * default proves anything on its own, but because it is what makes the other run a <i>difference</i>.
 */
public final class Http3ServerQuicSettingsWiringTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final String TICKETS_PER_HANDSHAKE = "QuicConnection.sessionTicketsPerHandshake";
	private static final String TICKET_AGE_TOLERANCE = "QuicConnection.ticketAgeTolerance";

	private static final int NON_DEFAULT_TICKETS = 5;
	private static final long NON_DEFAULT_TOLERANCE_MILLIS = 30_000L;

	@Test
	public void theDefaultsAreExactlyTheValuesThatMaskedTheDefect() {
		assertEquals(2, QuicConnectionSettings.DEFAULT_SESSION_TICKETS_PER_HANDSHAKE);
		assertEquals(Duration.ofSeconds(10), QuicConnectionSettings.DEFAULT_TICKET_AGE_TOLERANCE);

		long[] observed = Http3TicketSettingsProbe.run();

		assertArrayEquals("2 tickets per handshake, a 10 s tolerance, 2 tickets stored, no buffer leaked",
			new long[] {2, 10_000L, 2, 0}, observed);
	}

	@Test
	public void aConfiguredTicketCountAndAgeToleranceBothReachTheTlsLayer() throws Exception {
		long[] observed = runIsolated();

		assertEquals("sessionTicketsPerHandshake never left QuicConnectionSettings",
			NON_DEFAULT_TICKETS, observed[0]);
		assertEquals("ticketAgeTolerance never left QuicConnectionSettings",
			NON_DEFAULT_TOLERANCE_MILLIS, observed[1]);
		assertEquals("the configured count is what the peer actually received",
			NON_DEFAULT_TICKETS, observed[2]);
		assertEquals("the isolated run leaked a buffer", 0, observed[3]);

		assertEquals("the override must not have escaped into this loader",
			2, QuicConnectionSettings.DEFAULT_SESSION_TICKETS_PER_HANDSHAKE);
	}

	/**
	 * Runs {@link Http3TicketSettingsProbe} against a fresh copy of every class on this class path, with
	 * the two system properties set before that copy's {@code QuicConnectionSettings} first loads. The
	 * properties are cleared before returning, and this loader's own constants were resolved long ago,
	 * so nothing outside the isolated run can see them.
	 */
	private static long[] runIsolated() throws Exception {
		List<URL> urls = new ArrayList<>();
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
			urls.add(Path.of(entry).toUri().toURL());
		}

		System.setProperty(TICKETS_PER_HANDSHAKE, Integer.toString(NON_DEFAULT_TICKETS));
		System.setProperty(TICKET_AGE_TOLERANCE, (NON_DEFAULT_TOLERANCE_MILLIS / 1000) + " seconds");
		try (URLClassLoader loader =
				 new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
			Class<?> settings = loader.loadClass(QuicConnectionSettings.class.getName());
			assertEquals("the isolated copy resolved the same stale constants, so it proves nothing",
				NON_DEFAULT_TICKETS, settings.getField("DEFAULT_SESSION_TICKETS_PER_HANDSHAKE").get(null));

			Class<?> probe = loader.loadClass(Http3TicketSettingsProbe.class.getName());
			return (long[]) probe.getMethod("run").invoke(null);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof Exception cause) throw cause;
			throw new AssertionError(e.getCause());
		} finally {
			System.clearProperty(TICKETS_PER_HANDSHAKE);
			System.clearProperty(TICKET_AGE_TOLERANCE);
		}
	}
}
