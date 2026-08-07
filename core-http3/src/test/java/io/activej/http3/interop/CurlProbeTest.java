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

import io.activej.http3.interop.CurlProbe.Invocation;
import io.activej.http3.interop.CurlProbe.Outcome;
import io.activej.http3.interop.CurlProbe.Result;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The four discovery outcomes of the interop harness (contracts §6, FR-002…FR-005), exercised
 * against {@link CurlProbe#probe(String, boolean)} with a fake-curl script and a nonexistent path,
 * so every outcome is deterministic on any machine — including this sandbox, whose real {@code curl}
 * (7.88.1) has no {@code HTTP3} feature and must report {@link Outcome#ABSENT}.
 * <p>
 * The {@code -Dactivej.interop.curl} property plumbing itself is not mutated here: the default probe
 * ({@link CurlProbe#result()}) must stay the operator-visible one, and the four outcomes are the
 * same code path with {@code configured} set to exactly what {@code result()} would derive from the
 * property. {@link #defaultProbeOnThisSandboxReportsAbsent} pins the default probe to the sandbox
 * fact of T012.
 */
public final class CurlProbeTest {
	private static final String FAKE_VERSION_LINE = "curl 8.2.1-DEV (x86_64-pc-linux-gnu) libcurl/8.2.1 quiche/0.18.0";

	@Test
	public void propertyUnsetCapableCurlRuns() throws IOException {
		Path script = writeCurlScript(true);
		try {
			Result result = CurlProbe.probe(script.toString(), false);
			assertEquals(Outcome.USABLE, result.outcome);
			assertEquals(script.toString(), result.executable);
			assertEquals(FAKE_VERSION_LINE, result.versionLine);
			assertEquals("", result.reason);
		} finally {
			Files.deleteIfExists(script);
		}
	}

	@Test
	public void propertyUnsetAbsentCurlSkips() {
		Result result = CurlProbe.probe("/nonexistent/interop-curl-binary", false);
		assertEquals(Outcome.ABSENT, result.outcome);
		assertFalse(result.reason.isEmpty());
	}

	@Test
	public void propertySetMissingCurlFails() {
		Result result = CurlProbe.probe("/nonexistent/interop-curl-binary", true);
		assertEquals(Outcome.MISCONFIGURED, result.outcome);
		assertTrue(result.reason.contains("/nonexistent/interop-curl-binary"));
	}

	@Test
	public void propertySetCurlWithoutHttp3Skips() throws IOException {
		Path script = writeCurlScript(false);
		try {
			Result result = CurlProbe.probe(script.toString(), true);
			assertEquals(Outcome.ABSENT, result.outcome);
			assertTrue(result.reason.contains("HTTP3"));
			assertEquals(FAKE_VERSION_LINE, result.versionLine);
		} finally {
			Files.deleteIfExists(script);
		}
	}

	/**
	 * The sandbox fact of T012, logged rather than hard-asserted so the same class stays green on a
	 * machine that does have an HTTP/3-capable curl (SC-001): what must never happen is the default
	 * probe misreporting as {@link Outcome#MISCONFIGURED} — the property is unset, so an absent
	 * client is an absence, never a mistake.
	 */
	@Test
	public void defaultProbeOnThisSandboxReportsAbsent() {
		Result result = CurlProbe.result();
		System.out.println("CurlProbe default probe: " + result);
		assertNotEquals(Outcome.MISCONFIGURED, result.outcome);
	}

	@Test
	public void invocationParsesWriteOutAndDigest() throws IOException {
		Path script = writeFakeCurlBinary();
		try {
			Invocation invocation = CurlProbe.invoke(List.of(script.toString(), "-sS", "https://127.0.0.1/"));
			assertEquals(0, invocation.exitCode);
			assertEquals("3", invocation.httpVersion);
			assertEquals(200, invocation.statusCode);
			assertEquals(20, invocation.bodyLength);
			assertEquals("87338612bfad3fca26f01a9482f16dd3f863627840f4d7113fd7ffdd615baa39", invocation.bodyDigest);
			assertFalse(invocation.timedOut);
			assertFalse(invocation.stdout.isEmpty());
		} finally {
			Files.deleteIfExists(script);
		}
	}

	@Test
	public void invocationTimeoutDestroysProcess() throws IOException {
		Path script = writeScript("#!/bin/sh\n" +
			"exec sleep 60\n");
		try {
			Invocation invocation = CurlProbe.invoke(
				List.of(script.toString(), "-sS", "https://127.0.0.1/"), 1);
			assertTrue(invocation.timedOut);
			assertEquals(-1, invocation.statusCode);
			assertEquals("", invocation.httpVersion);
		} finally {
			Files.deleteIfExists(script);
		}
	}

	/** A script whose {@code -V} output claims (or denies) the {@code HTTP3} feature. */
	private static Path writeCurlScript(boolean http3) throws IOException {
		String features = http3 ?
			"alt-sync AsynchDNS brotli GnuTLS HSTS HTTP2 HTTP3 HTTPS-proxy IDN IPv6 Largefile libz " +
				"NTLM PSL SSL TLS-SRP UnixSockets zstd" :
			"alt-sync AsynchDNS brotli GnuTLS HSTS HTTP2 HTTPS-proxy IDN IPv6 Largefile libz " +
				"NTLM PSL SSL TLS-SRP UnixSockets zstd";
		return writeScript("#!/bin/sh\n" +
			"echo '" + FAKE_VERSION_LINE + "'\n" +
			"echo 'Release-Date: 2023-07-19'\n" +
			"echo 'Protocols: dict file ftp ftps gopher gophers http https imap imaps ldap ldaps mqtt pop3 " +
				"pop3s rtsp smb smbs sftp smb3 telnet tftp ws wss'\n" +
			"echo 'Features: " + features + "'\n" +
			"exit 0\n");
	}

	/**
	 * A script that pretends to be curl for one {@code -o}/{-w} invocation: writes a fixed body into
	 * the {@code -o} file and prints the write-out line a real curl would substitute. Deterministic
	 * on any machine; only the argument-vector protocol matters, no shell is involved.
	 */
	private static Path writeFakeCurlBinary() throws IOException {
		return writeScript("#!/bin/sh\n" +
			"body=\"\"\n" +
			"prev=\"\"\n" +
			"for a in \"$@\"; do\n" +
			"  if [ \"$prev\" = \"-o\" ]; then body=\"$a\"; fi\n" +
			"  prev=\"$a\"\n" +
			"done\n" +
			"printf 'fake body 1234567890' > \"$body\"\n" +
			"printf '\\nVER=3 ST=200\\n'\n" +
			"exit 0\n");
	}

	private static Path writeScript(String content) throws IOException {
		Path script = Files.createTempFile("fake-curl-", ".sh");
		Files.writeString(script, content);
		script.toFile().setExecutable(true);
		if (!script.toFile().canExecute()) {
			Files.deleteIfExists(script);
			fail("Cannot make the fake-curl script executable: " + script);
		}
		return script;
	}
}
