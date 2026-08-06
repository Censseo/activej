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

package io.activej.http3.interop;

import io.activej.http3.Http3Settings;
import io.activej.http3.interop.CurlProbe.Invocation;
import io.activej.http3.interop.CurlProbe.Outcome;
import io.activej.http3.interop.CurlProbe.Result;
import io.activej.test.rules.ByteBufRule;
import org.junit.Assume;
import org.junit.AssumptionViolatedException;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The automated curl interop suite (T013, US1): seven cases driving a real {@link io.activej.http3.Http3Server}
 * with a real foreign HTTP/3 client (contracts §6, FR-001, FR-006).
 * <p>
 * <b>Discovery before anything else</b> (FR-002…FR-005): {@link #requireUsableClient()} runs first. An
 * {@link Outcome#ABSENT} client — this sandbox's curl 7.88.1 has no {@code HTTP3} feature — skips the
 * whole class through a JUnit assumption, so <b>no server is ever started</b> (FR-004, SC-002); an
 * {@link Outcome#MISCONFIGURED} one — the property set to a path that cannot run — fails the class
 * rather than skipping (FR-005).
 * <p>
 * <b>Class name</b> (FR-001a): the {@code …InteropTest} suffix is what makes {@code -Dtest='*Interop*'}
 * select exactly this suite while Surefire's default {@code *Test.java} include still never runs the
 * two manual {@code main} programs beside it.
 * <p>
 * <b>Fixture shape</b>: each case stands up its own {@link Http3ServerReactorFixture} inside the
 * method and closes it in a {@code finally} — construction is reachable only from test methods, and
 * no test method runs when the assumption failed, so the skip path provably constructs nothing
 * (FR-004). {@link #serverStarted} records every construction; {@link #skipPathStartsNoServer} is a
 * class rule that asserts it stayed {@code false} while the class was being skipped (T023).
 * <p>
 * <b>Assertion discipline</b>: version 3 is asserted on every case through the single shared
 * {@link #assertHttpVersion3} — a 200 over HTTP/1.1 or HTTP/2 is a failure, never a pass (FR-007);
 * bodies are compared by length and SHA-256 digest only; every failure message is built by
 * {@link #failureMessage} from the case name, the command line, the exit status and the captured
 * stdout/stderr plus lengths and digests — never body bytes or header field values, which are
 * redacted (FR-008, FR-013, T022).
 * <p>
 * ⚠ The seven cases are <b>written-but-unexercised</b> on this machine: with no HTTP/3-capable curl
 * they skip, which is the specified behaviour here (SC-002). They are the specification of interop
 * for a capable curl (SC-001, SC-003) and await T058's run against one.
 */
public final class Http3CurlInteropTest {
	private static final String HTTP3_OPTION = "--http3";
	/** Case 2 body size. */
	private static final int SMALL_BODY_SIZE = 512;
	/** Case 3 body size: ≥ 2 MiB — larger than the 256 kB stream and 1 MB connection windows,
	 * smaller than the 100 MB body cap (SC-003). */
	private static final int LARGE_BODY_SIZE = 2 * 1024 * 1024;
	/** Case 5 concurrency. */
	private static final int CONCURRENT_PROCESSES = 4;
	/** The custom request fields case 6 sends; the servlet echoes every {@code x-interop-*} field. */
	private static final String REQUEST_FIELD_1 = "x-interop-req";
	private static final String REQUEST_FIELD_2 = "x-interop-extra";
	private static final String REQUEST_FIELD_VALUE_1 = "interop-req-value-1";
	private static final String REQUEST_FIELD_VALUE_2 = "interop-extra-value-2";
	/** The body {@code /headers} answers; must match {@link InteropTestServlet}'s route. */
	private static final String HEADERS_BODY = "headers echoed\n";
	/** The write-out line shape {@link CurlProbe} appends per transfer. */
	private static final Pattern VER_ST = Pattern.compile("VER=(\\S+) ST=(\\d+)");

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/**
	 * T023's in-suite proof of FR-004: runs around the whole class and, when the class is being
	 * skipped, asserts the fixture was never constructed before letting the assumption through.
	 * Without it the no-server-started property would be an argument; with it a future change that
	 * moved fixture construction before the assumption fails this class.
	 */
	@ClassRule
	public static final TestRule skipPathStartsNoServer = Http3CurlInteropTest::guard;

	/** Set only by {@link #newFixture()} — the skip path must leave it false (FR-004, T023). */
	static volatile boolean serverStarted;

	/**
	 * The class-level gate (FR-004, FR-005): a MISCONFIGURED client fails, an ABSENT one skips every
	 * case, and nothing — the assumption included — runs before this method.
	 */
	@BeforeClass
	public static void requireUsableClient() {
		Result probe = CurlProbe.result();
		if (probe.outcome == Outcome.MISCONFIGURED) {
			// An explicit configuration that cannot work is a mistake, not an absence (FR-005):
			// fail the class rather than skip it, and start no server for a client that cannot talk to it.
			fail("interop curl '" + probe.executable + "' configured via -D" + CurlProbe.CURL_PROPERTY +
				" is not usable: " + probe.reason);
		}
		Assume.assumeTrue(
			"no HTTP/3-capable curl (probed '" + probe.executable + "'" +
				(probe.versionLine.isEmpty() ? "" : ": " + probe.versionLine) + ")" +
				(probe.reason.isEmpty() ? "" : " — " + probe.reason) +
				"; set -D" + CurlProbe.CURL_PROPERTY + "=<path to an HTTP/3-capable client> to run the cases",
			probe.outcome == Outcome.USABLE);
	}

	/**
	 * Case 1 — {@code GET /} returns 200 over HTTP version 3, body byte-identical to the servlet's
	 * fixed body (acceptance 1). ⚠ unverifiable-in-sandbox.
	 */
	@Test
	public void get() {
		String caseName = "get";
		Http3ServerReactorFixture fixture = newFixture();
		try {
			List<String> command = curlCommand("https://127.0.0.1:" + fixture.port() + "/");
			Invocation invocation = CurlProbe.invoke(command);
			byte[] expectedBody = InteropTestServlet.FIXED_BODY.getBytes(StandardCharsets.UTF_8);
			verify(caseName, command, invocation, 200, expectedBody.length, sha256Hex(expectedBody));
		} finally {
			fixture.close();
		}
	}

	/**
	 * Case 2 — a 512-byte body POSTed to {@code /echo} comes back byte-identical (acceptance 2).
	 * ⚠ unverifiable-in-sandbox.
	 */
	@Test
	public void postSmallEcho() throws IOException {
		String caseName = "postSmallEcho";
		Http3ServerReactorFixture fixture = newFixture();
		Path bodyFile = null;
		try {
			byte[] body = patternBody(SMALL_BODY_SIZE, 0x2A);
			bodyFile = writeBodyFile(body);
			List<String> command = curlCommand("--data-binary", "@" + bodyFile,
				"https://127.0.0.1:" + fixture.port() + "/echo");
			Invocation invocation = CurlProbe.invoke(command);
			verify(caseName, command, invocation, 200, body.length, sha256Hex(body));
		} finally {
			fixture.close();
			if (bodyFile != null) {
				Files.deleteIfExists(bodyFile);
			}
		}
	}

	/**
	 * Case 3 — a ≥ 2 MiB body POSTed to {@code /echo} comes back byte-identical, proving flow-control
	 * credit is issued and honoured across the stream and connection windows (acceptance 3, SC-003).
	 * ⚠ unverifiable-in-sandbox.
	 */
	@Test
	public void postLargeEcho() throws IOException {
		String caseName = "postLargeEcho";
		Http3ServerReactorFixture fixture = newFixture();
		Path bodyFile = null;
		try {
			byte[] body = patternBody(LARGE_BODY_SIZE, 0x5A);
			bodyFile = writeBodyFile(body);
			List<String> command = curlCommand("--data-binary", "@" + bodyFile,
				"https://127.0.0.1:" + fixture.port() + "/echo");
			Invocation invocation = CurlProbe.invoke(command);
			verify(caseName, command, invocation, 200, body.length, sha256Hex(body));
		} finally {
			fixture.close();
			if (bodyFile != null) {
				Files.deleteIfExists(bodyFile);
			}
		}
	}

	/**
	 * Case 4 — three URLs in one curl process via {@code --next}; every transfer answers 200 over
	 * version 3, and the server observes exactly one accepted connection — read only after the
	 * process was reaped, which {@link CurlProbe#invoke} guarantees by construction (acceptance 4,
	 * spec §Edge Cases). ⚠ unverifiable-in-sandbox.
	 */
	@Test
	public void multipleRequestsOneConnection() {
		String caseName = "multipleRequestsOneConnection";
		Http3ServerReactorFixture fixture = newFixture();
		try {
			String base = "https://127.0.0.1:" + fixture.port();
			List<String> command = curlCommand(
				base + "/", "--next", base + "/headers", "--next", base + "/");
			Invocation invocation = CurlProbe.invoke(command);

			// The write-out line is printed after every transfer: each one must read VER=3 ST=200.
			List<String> writeOuts = invocation.stdout.lines()
				.filter(line -> line.startsWith("VER="))
				.toList();
			assertEquals(failureMessage(caseName, command, invocation) +
				" — expected one write-out line per --next transfer",
				3, writeOuts.size());
			for (String line : writeOuts) {
				assertEquals(failureMessage(caseName, command, invocation) +
					" — every transfer must answer 200 over version 3", "VER=3 ST=200", line);
			}
			// The -o file holds the last transfer's body — GET / again.
			byte[] expectedBody = InteropTestServlet.FIXED_BODY.getBytes(StandardCharsets.UTF_8);
			verify(caseName, command, invocation, 200, expectedBody.length, sha256Hex(expectedBody));

			// Counters are read only now, with the process reaped (spec §Edge Cases).
			assertEquals(failureMessage(caseName, command, invocation) +
				" — one curl process must use exactly one connection", 1, fixture.connectionsAccepted());
			assertEquals(failureMessage(caseName, command, invocation) +
				" — three transfers must serve three requests", 3, fixture.requestsServed());
		} finally {
			fixture.close();
		}
	}

	/**
	 * Case 5 — {@value #CONCURRENT_PROCESSES} curl processes at once, each POSTing a distinct body to
	 * {@code /echo}; every response is correct and unmixed, and the counters are read only after all
	 * processes are reaped (acceptance 5, spec §Edge Cases). ⚠ unverifiable-in-sandbox.
	 */
	@Test
	public void concurrentClientProcesses() throws Exception {
		String caseName = "concurrentClientProcesses";
		Http3ServerReactorFixture fixture = newFixture();
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_PROCESSES);
		try {
			List<Future<Invocation>> futures = new ArrayList<>();
			for (int seed = 0; seed < CONCURRENT_PROCESSES; seed++) {
				int finalSeed = seed;
				futures.add(pool.submit(() -> {
					byte[] body = patternBody(SMALL_BODY_SIZE, finalSeed);
					Path bodyFile = writeBodyFile(body);
					try {
						List<String> command = curlCommand("--data-binary", "@" + bodyFile,
							"https://127.0.0.1:" + fixture.port() + "/echo");
						Invocation invocation = CurlProbe.invoke(command);
						verify(caseName + "#" + finalSeed, command, invocation, 200, body.length, sha256Hex(body));
						return invocation;
					} finally {
						Files.deleteIfExists(bodyFile);
					}
				}));
			}
			// Every process is reaped before any counter is read (spec §Edge Cases).
			for (Future<Invocation> future : futures) {
				try {
					future.get(CurlProbe.TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof RuntimeException) throw (RuntimeException) cause;
					if (cause instanceof Error) throw (Error) cause;
					throw new IllegalStateException("Concurrent interop case failed", cause);
				}
			}
			assertEquals(caseName + ": " + CONCURRENT_PROCESSES + " processes must serve " +
				CONCURRENT_PROCESSES + " requests", CONCURRENT_PROCESSES, fixture.requestsServed());
			assertEquals(caseName + ": " + CONCURRENT_PROCESSES + " processes must use " +
				CONCURRENT_PROCESSES + " connections", CONCURRENT_PROCESSES, fixture.connectionsAccepted());
		} finally {
			pool.shutdownNow();
			fixture.close();
		}
	}

	/**
	 * Case 6 — custom request fields arrive intact (the servlet's {@code /headers} route echoes every
	 * {@code x-interop-*} request field as a response field) and the custom response fields come back
	 * intact. Assertions name fields, never values; a mismatch is reported as lengths only (FR-013,
	 * acceptance 6). ⚠ unverifiable-in-sandbox.
	 */
	@Test
	public void customHeaderFields() {
		String caseName = "customHeaderFields";
		Http3ServerReactorFixture fixture = newFixture();
		try {
			List<String> command = curlCommand(
				"-D", "-", // dump the response field section to stdout so the echoes are observable
				"-H", REQUEST_FIELD_1 + ": " + REQUEST_FIELD_VALUE_1,
				"-H", REQUEST_FIELD_2 + ": " + REQUEST_FIELD_VALUE_2,
				"https://127.0.0.1:" + fixture.port() + "/headers");
			Invocation invocation = CurlProbe.invoke(command);
			byte[] expectedBody = HEADERS_BODY.getBytes(StandardCharsets.UTF_8);
			verify(caseName, command, invocation, 200, expectedBody.length, sha256Hex(expectedBody));

			Map<String, String> responseFields = responseFields(invocation.stdout);
			assertFieldEchoed(caseName, command, invocation, responseFields, REQUEST_FIELD_1, REQUEST_FIELD_VALUE_1);
			assertFieldEchoed(caseName, command, invocation, responseFields, REQUEST_FIELD_2, REQUEST_FIELD_VALUE_2);
		} finally {
			fixture.close();
		}
	}

	/**
	 * Case 7 — after a completed exchange, closing the server finishes it within the GOAWAY drain
	 * ceiling {@link Http3Settings#DEFAULT_SHUTDOWN_TIMEOUT} and leaks nothing (the class's
	 * {@link ByteBufRule} is the leak verdict; acceptance 7). ⚠ unverifiable-in-sandbox.
	 */
	@Test
	public void gracefulServerClose() {
		String caseName = "gracefulServerClose";
		Http3ServerReactorFixture fixture = newFixture();
		try {
			List<String> command = curlCommand("https://127.0.0.1:" + fixture.port() + "/");
			Invocation invocation = CurlProbe.invoke(command);
			byte[] expectedBody = InteropTestServlet.FIXED_BODY.getBytes(StandardCharsets.UTF_8);
			verify(caseName, command, invocation, 200, expectedBody.length, sha256Hex(expectedBody));

			long closeStarted = System.nanoTime();
			fixture.close();
			long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);
			long ceiling = Http3Settings.DEFAULT_SHUTDOWN_TIMEOUT.toMillis();
			assertTrue(failureMessage(caseName, command, invocation) +
				" — server close took " + closeMillis + " ms, the GOAWAY drain ceiling is " + ceiling + " ms",
				closeMillis < ceiling);
		} finally {
			fixture.close(); // no-op when the measured close already ran; closes on every failure path
		}
	}

	/**
	 * T021 — the one place HTTP version 3 is asserted, reached by every case: a 200 over HTTP/1.1 or
	 * HTTP/2 can never read as a pass (FR-007). Every write-out line is checked, so a
	 * multi-transfer case cannot pass on its last line alone.
	 */
	private static void assertHttpVersion3(String caseName, List<String> command, Invocation invocation) {
		Matcher matcher = VER_ST.matcher(invocation.stdout);
		boolean found = false;
		while (matcher.find()) {
			found = true;
			if (!"3".equals(matcher.group(1))) {
				fail(failureMessage(caseName, command, invocation) +
					" — negotiated HTTP version '" + matcher.group(1) + "', must be exactly 3 (FR-007)");
			}
		}
		if (!found) {
			fail(failureMessage(caseName, command, invocation) +
				" — no write-out line parsed, cannot assert HTTP version 3 (FR-007)");
		}
	}

	/**
	 * T022 — the shared verify: timeout, exit status, version 3, status and body (by length and
	 * digest) are checked here, and every failure goes through {@link #failureMessage}.
	 */
	private static void verify(String caseName, List<String> command, Invocation invocation,
		int expectedStatus, long expectedBodyLength, String expectedBodyDigest) {
		if (invocation.timedOut) {
			fail(failureMessage(caseName, command, invocation) +
				" — the client did not finish within " + CurlProbe.TIMEOUT_SECONDS + " s and was destroyed (FR-008)");
		}
		if (invocation.exitCode != 0) {
			fail(failureMessage(caseName, command, invocation) + " — the client exited " + invocation.exitCode);
		}
		assertHttpVersion3(caseName, command, invocation);
		if (invocation.statusCode != expectedStatus) {
			fail(failureMessage(caseName, command, invocation) +
				" — status " + invocation.statusCode + " (expected " + expectedStatus + ")");
		}
		if (invocation.bodyLength != expectedBodyLength || !invocation.bodyDigest.equals(expectedBodyDigest)) {
			fail(failureMessage(caseName, command, invocation) + " — body mismatch: expected " + expectedBodyLength +
				" bytes, sha256 " + expectedBodyDigest + "; got " + invocation.bodyLength +
				" bytes, sha256 " + invocation.bodyDigest);
		}
	}

	/** T022 — every failure message is built here: case name, command line, exit status, captured
	 * stdout/stderr, lengths and digests. Body bytes and field values never enter (FR-008, FR-013). */
	private static String failureMessage(String caseName, List<String> command, Invocation invocation) {
		return "case '" + caseName + "' failed\n" +
			"  command: " + commandLine(command) + "\n" +
			"  exit: " + invocation.exitCode + (invocation.timedOut ? " (timed out)" : "") + "\n" +
			"  stdout:\n" + redact(invocation.stdout) + "\n" +
			"  stderr:\n" + redact(invocation.stderr) + "\n" +
			"  body: " + invocation.bodyLength + " bytes, sha256 " + invocation.bodyDigest;
	}

	/** FR-013: a header field value never reaches a message — the value of any {@code x-interop-*}
	 * line is stripped from stdout/stderr, and the same redaction is applied to {@code -H} arguments
	 * of the command line. Field names stay; values go. */
	private static String redact(String text) {
		return text.lines()
			.map(line -> {
				int colon = line.indexOf(':');
				if (colon > 0) {
					String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
					if (name.startsWith(InteropTestServlet.CUSTOM_FIELD_PREFIX)) {
						return name + ": <redacted>";
					}
				}
				return line;
			})
			.collect(Collectors.joining("\n"));
	}

	private static String commandLine(List<String> command) {
		return command.stream()
			.map(arg -> {
				int colon = arg.indexOf(':');
				if (colon > 0 && arg.substring(0, colon).trim().toLowerCase(Locale.ROOT)
					.startsWith(InteropTestServlet.CUSTOM_FIELD_PREFIX)) {
					return arg.substring(0, colon) + ": <redacted>";
				}
				return arg;
			})
			.collect(Collectors.joining(" "));
	}

	/** The {@code x-interop-*} response fields echoed by {@code /headers}, lowercased names. */
	private static Map<String, String> responseFields(String stdout) {
		Map<String, String> fields = new HashMap<>();
		for (String line : stdout.split("\\R")) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
				if (name.startsWith(InteropTestServlet.CUSTOM_FIELD_PREFIX)) {
					fields.put(name, line.substring(colon + 1).trim());
				}
			}
		}
		return fields;
	}

	/** Asserts one echoed field by name; the failure reports lengths only, never the value (FR-013). */
	private static void assertFieldEchoed(String caseName, List<String> command, Invocation invocation,
		Map<String, String> responseFields, String fieldName, String expectedValue) {
		String actual = responseFields.get(fieldName.toLowerCase(Locale.ROOT));
		if (actual == null) {
			fail(failureMessage(caseName, command, invocation) +
				" — response field '" + fieldName + "' was not echoed");
		}
		if (!actual.equals(expectedValue)) {
			fail(failureMessage(caseName, command, invocation) + " — response field '" + fieldName +
				"' echoed " + actual.length() + " value bytes, expected " + expectedValue.length());
		}
	}

	/** The argument vector is the operator-probed binary plus the protocol flags — no shell (FR-008). */
	private static List<String> curlCommand(String... args) {
		List<String> command = new ArrayList<>();
		command.add(CurlProbe.result().executable);
		command.add("-sS");
		command.add(HTTP3_OPTION);
		command.add("-k");
		command.addAll(List.of(args));
		return command;
	}

	private static Http3ServerReactorFixture newFixture() {
		Http3ServerReactorFixture fixture = new Http3ServerReactorFixture(InteropTestServlet::create);
		serverStarted = true;
		return fixture;
	}

	/** T023 — runs around the whole class; when the class is being skipped it proves the fixture was
	 * never built before letting the assumption through (FR-004). */
	private static Statement guard(Statement base, Description description) {
		return new Statement() {
			@Override
			public void evaluate() throws Throwable {
				try {
					base.evaluate();
				} catch (AssumptionViolatedException skip) {
					assertFalse("the skip path must never construct the interop server fixture (FR-004)",
						serverStarted);
					throw skip;
				}
			}
		};
	}

	/** A deterministic body: distinct seeds give distinct bytes, so a mixed-up exchange is a mismatch. */
	private static byte[] patternBody(int length, int seed) {
		byte[] body = new byte[length];
		for (int i = 0; i < length; i++) {
			body[i] = (byte) (seed + i * 31);
		}
		return body;
	}

	private static Path writeBodyFile(byte[] body) throws IOException {
		Path file = Files.createTempFile("activej-interop-request-", ".body");
		Files.write(file, body);
		return file;
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 is always available", e);
		}
	}
}
