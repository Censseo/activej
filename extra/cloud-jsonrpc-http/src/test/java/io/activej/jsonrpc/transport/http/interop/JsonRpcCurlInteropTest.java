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

package io.activej.jsonrpc.transport.http.interop;

import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.jsonrpc.transport.http.interop.CurlProbe.Invocation;
import io.activej.jsonrpc.transport.http.interop.CurlProbe.Outcome;
import io.activej.jsonrpc.transport.http.interop.CurlProbe.Result;
import io.activej.jsonrpc.transport.http.interop.InteropVectors.Vector;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

/**
 * The live interoperability tier (T052 — FR-063…FR-063c, ADR-027): the four frozen
 * {@code curl}-origin vectors of {@code http-vectors.json} are replayed against a <b>real
 * server on port {@code :0}</b> by a <b>real foreign {@code curl}</b> — no shell, no
 * {@code core-http3} artifact — and each response must match its frozen vector.
 * <p>
 * <b>Discovery before anything else</b> (FR-063a/b): {@link #requireUsableClient()} runs first. An
 * {@link Outcome#ABSENT} client — the property unset and no usable {@code curl} on {@code PATH} —
 * skips the whole class through a JUnit assumption with a stated reason, and <b>no server is ever
 * started</b>; an {@link Outcome#MISCONFIGURED} one — the property explicitly set to a path that
 * cannot work — fails the class rather than skipping (FR-063b).
 * <p>
 * <b>Assertion discipline</b> (FR-063c): every invocation is timeout-bounded and force-killed, with
 * stdout, stderr and the exit status captured; the body is compared by length and SHA-256 digest
 * against the frozen vector's bytes — no peer body byte ever reaches a failure message. The
 * asserted headers are read from curl's own header dump ({@code -D -}) and compared exactly; a
 * mismatch reports lengths, never the peer's value. HTTP version {@code 1.1} is asserted on every
 * case — this wire contract is HTTP/1.1, so a foreign client negotiating anything else is a
 * failure, not a pass.
 * <p>
 * The fixture shape mirrors {@code JsonRpcHttpRawExchange}: one server per case on the rule's
 * eventloop, the loop run on its own thread so the test thread can block on the subprocess, and a
 * time-bounded teardown on every path. A case is only reached when the class-level assumption
 * passed, so the skip path provably constructs no server.
 */
public final class JsonRpcCurlInteropTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private Eventloop eventloop;
	private JsonRpcServlet servlet;

	/**
	 * The class-level gate (FR-063b): a MISCONFIGURED client fails, an ABSENT one skips every case
	 * with a stated reason, and nothing — the assumption included — runs before this method.
	 */
	@BeforeClass
	public static void requireUsableClient() {
		Result probe = CurlProbe.result();
		if (probe.outcome == Outcome.MISCONFIGURED) {
			// An explicit configuration that cannot work is a mistake, not an absence (FR-063b):
			// fail the class rather than skip it, and start no server for a client that cannot talk to it.
			fail("interop curl '" + probe.executable + "' configured via -D" + CurlProbe.CURL_PROPERTY +
				" is not usable: " + probe.reason);
		}
		Assume.assumeTrue(
			"no usable curl (probed '" + probe.executable + "'" +
				(probe.versionLine.isEmpty() ? "" : ": " + probe.versionLine) + ")" +
				(probe.reason.isEmpty() ? "" : " — " + probe.reason) +
				"; set -D" + CurlProbe.CURL_PROPERTY + "=<path to a curl> to run the live cases",
			probe.outcome == Outcome.USABLE);
	}

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		servlet = JsonRpcServlet.create(eventloop, dispatcher);
	}

	/** The frozen {@code curl-single-request} exchange, replayed by a real curl. */
	@Test
	public void singleRequest() throws Exception {
		replayWithCurl(vector("curl-single-request"));
	}

	/** The frozen {@code curl-notification} exchange, replayed by a real curl. */
	@Test
	public void notification() throws Exception {
		replayWithCurl(vector("curl-notification"));
	}

	/** The frozen {@code curl-batch} exchange, replayed by a real curl. */
	@Test
	public void batch() throws Exception {
		replayWithCurl(vector("curl-batch"));
	}

	/** The frozen {@code curl-rejection-get} exchange, replayed by a real curl. */
	@Test
	public void rejectionGet() throws Exception {
		replayWithCurl(vector("curl-rejection-get"));
	}

	/** One vector by its frozen name; a missing name fails the suite, not the lookup. */
	private static Vector vector(String name) {
		return InteropVectors.load().stream()
			.filter(v -> v.name().equals(name))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no frozen vector named '" + name + "'"));
	}

	/**
	 * One case: a real server on {@code :0} over the real servlet, one real curl process against it,
	 * the response verified against the frozen vector. The loop runs on its own thread — the test
	 * thread must be free to block on the subprocess — and the teardown is time-bounded on every
	 * path, assertion failures included.
	 */
	private void replayWithCurl(Vector vector) throws Exception {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(servlet)
			.build();
		server.listen();
		Thread loopThread = new Thread(eventloop);
		loopThread.start();
		try {
			List<String> command = curlCommand(vector, "http://127.0.0.1:" + server.port() + "/");
			Invocation invocation = CurlProbe.invoke(command);
			verifyAgainstVector(vector, command, invocation);
		} finally {
			try {
				server.closeFuture().get(10, TimeUnit.SECONDS);
			} finally {
				loopThread.join(10_000);
				if (loopThread.isAlive()) {
					throw new IllegalStateException("the eventloop thread did not stop: a case left it running");
				}
			}
		}
	}

	/**
	 * The argument vector for one case: the probed binary, then {@code -D -} so the response
	 * headers are observable in stdout, then the request shape of the frozen vector — method,
	 * recorded {@code Content-Type}, recorded body verbatim. No shell (FR-063c). The body goes to
	 * curl's {@code -o} temp file and the {@code -w} write-out is appended by {@link CurlProbe}.
	 */
	private static List<String> curlCommand(Vector vector, String url) {
		List<String> command = new ArrayList<>();
		command.add(CurlProbe.result().executable);
		command.add("-sS");
		command.add("-D"); // dump the response head to stdout, so asserted headers are observable
		command.add("-");
		if (vector.method().equals("POST")) {
			command.add("--data-binary");
			command.add(vector.requestBody());
			command.add("-H");
			command.add("Content-Type: " + vector.requestHeaders().get("Content-Type"));
		}
		command.add(url);
		return command;
	}

	/**
	 * The shared verify: timeout, exit status, HTTP version 1.1, then the frozen vector's status,
	 * body (by length and SHA-256 digest — FR-063c) and asserted headers (by name, with lengths in
	 * the message, never the peer's value). Every failure is built by {@link #failureMessage}, which
	 * carries stdout/stderr and the write-out but no response byte.
	 */
	private static void verifyAgainstVector(Vector vector, List<String> command, Invocation invocation) {
		String caseName = vector.name();
		if (invocation.timedOut) {
			fail(failureMessage(caseName, command, invocation) +
				" — the client did not finish within " + CurlProbe.TIMEOUT_SECONDS +
				" s and was destroyed (FR-063c)");
		}
		if (invocation.exitCode != 0) {
			fail(failureMessage(caseName, command, invocation) + " — the client exited " + invocation.exitCode);
		}
		if (!invocation.httpVersion.equals("1.1")) {
			fail(failureMessage(caseName, command, invocation) +
				" — HTTP version '" + invocation.httpVersion + "', this wire contract is HTTP/1.1");
		}
		if (invocation.statusCode != vector.status()) {
			fail(failureMessage(caseName, command, invocation) +
				" — status " + invocation.statusCode + " (expected " + vector.status() + ")");
		}
		byte[] expectedBody = vector.bodyAbsent() ? new byte[0] : vector.body().getBytes(UTF_8);
		if (invocation.bodyLength != expectedBody.length ||
			!invocation.bodyDigest.equals(sha256Hex(expectedBody))) {
			fail(failureMessage(caseName, command, invocation) +
				" — body mismatch: expected " + expectedBody.length + " bytes, sha256 " +
				sha256Hex(expectedBody) + "; got " + invocation.bodyLength + " bytes, sha256 " +
				invocation.bodyDigest);
		}
		for (Map.Entry<String, String> expected : vector.expectHeaders().entrySet()) {
			assertResponseHeader(caseName, command, invocation, expected.getKey(), expected.getValue());
		}
	}

	/**
	 * One asserted header, read from the {@code -D -} dump in stdout. The name is matched
	 * case-insensitively; a missing header or a value mismatch reports lengths only (FR-063c).
	 */
	private static void assertResponseHeader(String caseName, List<String> command, Invocation invocation,
		String expectedName, String expectedValue) {
		String prefix = expectedName.toLowerCase(Locale.ROOT) + ":";
		String actualLine = invocation.stdout.lines()
			.map(String::trim)
			.filter(line -> line.toLowerCase(Locale.ROOT).startsWith(prefix))
			.findFirst()
			.orElse(null);
		if (actualLine == null) {
			fail(failureMessage(caseName, command, invocation) +
				" — response header '" + expectedName + "' is absent");
		}
		String actualValue = actualLine.substring(actualLine.indexOf(':') + 1).trim();
		if (!actualValue.equals(expectedValue)) {
			fail(failureMessage(caseName, command, invocation) +
				" — response header '" + expectedName + "' carried " + actualValue.length() +
				" value bytes, expected " + expectedValue.length());
		}
	}

	/** FR-063c: every failure message carries the case, the command line, the exit status and the
	 * captured streams — never a body byte or a header value the peer chose. */
	private static String failureMessage(String caseName, List<String> command, Invocation invocation) {
		return "case '" + caseName + "' failed\n" +
			"  command: " + commandLine(command) + "\n" +
			"  exit: " + invocation.exitCode + (invocation.timedOut ? " (timed out)" : "") + "\n" +
			"  write-out: " + writeOutLine(invocation) + "\n" +
			"  stderr:\n" + invocation.stderr + "\n" +
			"  body: " + invocation.bodyLength + " bytes, sha256 " + invocation.bodyDigest;
	}

	private static String commandLine(List<String> command) {
		return String.join(" ", command);
	}

	/** The parsed {@code VER=… ST=…} line, for messages; the raw stdout holds the peer's header dump. */
	private static String writeOutLine(Invocation invocation) {
		return invocation.stdout.lines()
			.filter(line -> line.startsWith("VER="))
			.findFirst()
			.orElse("(no write-out line parsed)");
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 is always available", e);
		}
	}
}
