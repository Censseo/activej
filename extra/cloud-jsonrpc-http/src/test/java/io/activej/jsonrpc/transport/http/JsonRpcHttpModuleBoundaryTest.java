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

package io.activej.jsonrpc.transport.http;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * The boundary guard for {@code extra/cloud-jsonrpc-http} (FR-006).
 * <p>
 * Properties are asserted by scanning the module's {@code src/main} sources as text:
 * <ul>
 *     <li>no source file imports an {@code activej-rpc} type — feature 012's refusal of
 *     {@code io.activej.rpc} was on semantics (ADR-030), not on the module the code lived in, and
 *     survives the move into this transport module;</li>
 *     <li>no source file imports a {@code core-codegen} type — the transport is built on
 *     {@code core-http} and {@code activej-jsonrpc}, and codegen has no role here;</li>
 *     <li>no source file imports {@code org.slf4j} — even though {@code core-http} itself logs,
 *     every failure this module can observe already has a defined destination: the dispatcher's
 *     {@code withFailureHandler}, the calling {@code Promise}, or the reactor's fatal-error handler.
 *     A logger would be a fourth destination with no reader (FR-006);</li>
 *     <li>no test source mentions {@code getFreePort} — every server in this module's tests binds
 *     port {@code 0} and is asked where it landed (FR-050a, ADR-028).</li>
 *     <li>the <b>negative space</b> (T066a): the requirements that say MUST NOT need a check, not
 *     silence — no per-call deadline/timeout setting (FR-090), no in-flight concurrency bound
 *     (FR-091), no auth/CORS/rate-limiting code (FR-092), no JMX annotation (FR-093), no WebSocket
 *     and no raw-TCP code (FR-094), no HTTP/2- or HTTP/3-specific branch (FR-095), and no
 *     {@code GET} handling (FR-096). Each is asserted as the absence of the code tokens such an
 *     implementation could not avoid, one test per FR, on code lines only — documented prose about
 *     a rule is not a violation of it, so the Javadoc that <i>states</i> the negative space stays
 *     legal while the code that would <i>fill</i> it fails the build.</li>
 * </ul>
 * A plain text scan is deliberate — no bytecode or reflection scanning library exists in this
 * module's dependency set, and adding one to police a rule about dependencies would be
 * self-defeating. The test passes trivially while {@code src/main} is empty; that is the point —
 * it exists before the code it constrains.
 */
public class JsonRpcHttpModuleBoundaryTest {
	/** Package prefixes no source file of this module may import. */
	private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
		"io.activej.rpc",
		"io.activej.codegen",
		"org.slf4j"
	);

	private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$]+)\\s*;");

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void noForbiddenImport() {
		List<String> violations = findForbiddenImportViolations(mainRoot());
		for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
			List<String> forPrefix = violations.stream()
				.filter(v -> v.contains("(forbidden prefix " + prefix + ")"))
				.toList();
			assertTrue(prefix + " must stay refused in this module's src/main: " + forPrefix, forPrefix.isEmpty());
		}
	}

	@Test
	public void theScannerActuallyCatchesAViolation() throws IOException {
		// guards the guard: a scan that reports zero because it is broken must be distinguishable
		Path root = tmp.newFolder("negative-scan").toPath();
		writeSyntheticSource(root, "io.activej.jsonrpc.transport.http", "Misplaced",
			"io.activej.rpc.RpcClient");

		List<String> violations = findForbiddenImportViolations(root);

		assertTrue("a deliberately misplaced import must be reported exactly once: " + violations,
			violations.size() == 1 && violations.get(0).contains("io.activej.rpc"));
	}

	@Test
	public void noGetFreePortInTests() {
		// FR-050a, ADR-028: every server in this module's tests binds :0 and is asked where it
		// landed. getFreePort() is the registry's recorded anti-pattern; refusing it in source is
		// the same reasoning as the import scan — a rule about what the module's tests may contain.
		List<String> violations = findOccurrenceViolations(testRoot(), "getFreePort");
		assertTrue("TestUtils.getFreePort() must stay absent from this module's src/test (FR-050a, ADR-028): " + violations,
			violations.isEmpty());
	}

	@Test
	public void theOccurrenceScannerActuallyCatchesAViolation() throws IOException {
		// guards the guard: the occurrence scan must report, not return zero because it is broken
		Path root = tmp.newFolder("negative-scan-getfreeport").toPath();
		Path dir = root.resolve("io/activej/jsonrpc/transport/http");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("Misplaced.java"), """
			package io.activej.jsonrpc.transport.http;

			import io.activej.test.TestUtils;

			class Misplaced {
				int port = TestUtils.getFreePort();
			}
			""");

		List<String> violations = findOccurrenceViolations(root, "getFreePort");

		assertTrue("a deliberate getFreePort call must be reported exactly once: " + violations,
			violations.size() == 1 && violations.get(0).contains("getFreePort"));
	}

	@Test
	public void noGetArrayInMainSources() {
		// FR-026: the body-to-byte[] conversion is takeBody() then ByteBuf.asArray() — which copies
		// AND recycles — and ByteBuf.getArray(), which copies WITHOUT recycling, must appear nowhere
		// on that path. The scan is unconditional (T033): a review is not a build gate. Comment
		// lines are skipped, so JsonRpcServlet's own javadoc warning about getArray() is not a
		// violation, and asArray does not contain the needle getArray.
		List<String> violations = findOccurrenceViolations(mainRoot(), "getArray");
		assertTrue("ByteBuf.getArray() copies without recycling and must stay absent from src/main (FR-026): "
				   + violations,
			violations.isEmpty());
	}

	@Test
	public void theGetArrayScannerActuallyCatchesAViolation() throws IOException {
		// guards the guard: the getArray scan must report, not rot into a silent no-op
		Path root = tmp.newFolder("negative-scan-getarray").toPath();
		Path dir = root.resolve("io/activej/jsonrpc/transport/http");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("Misplaced.java"), """
			package io.activej.jsonrpc.transport.http;

			import io.activej.bytebuf.ByteBuf;

			class Misplaced {
				byte[] leak(ByteBuf body) {
					return body.getArray();
				}
			}
			""");

		List<String> violations = findOccurrenceViolations(root, "getArray");

		assertTrue("a deliberate getArray call must be reported exactly once: " + violations,
			violations.size() == 1 && violations.get(0).contains("getArray"));
	}

	// ---------------------------------------------------------------------------------------------------
	// The negative space (T066a) — FR-090…FR-096 as asserted source scans.
	// A deferred requirement with an owner is still a requirement: an unchecked one silently
	// acquires an implementation. Each needle is a code token such an implementation could not
	// avoid; comment lines are skipped (the module's own Javadoc documents these refusals), and a
	// needle that ever appears on a code line fails its FR's test by name.
	// ---------------------------------------------------------------------------------------------------

	private static final Map<String, List<String>> NEGATIVE_SPACE = Map.of(
		// FR-090: a per-call JSON-RPC deadline and a timeout setting are feature 09's, not this
		// module's. "timeout" and "Timeout" together cover a lowercase field and every
		// TimeoutException/READ_WRITE_TIMEOUT/withTimeout spelling; the inherited connection-level
		// bound (HttpServer.readWriteTimeout) is core-http's, and may be named in prose only.
		"FR-090 (no per-call deadline, no timeout setting)",
		List.of("deadline", "timeout", "Timeout"),
		// FR-091: an in-flight concurrency bound is feature 09's (maxInFlight is its name on
		// PendingCall); this module's inFlight set on the client is close-semantics bookkeeping
		// (FR-039/040), not a bound, and the needle deliberately does not match it.
		"FR-091 (no in-flight concurrency bound)",
		List.of("maxInFlight"),
		// FR-092: authentication, CORS and rate limiting compose through AsyncServlet decorators
		// (BasicAuthServlet, ServletWithStats); none is implemented here.
		"FR-092 (no auth, CORS or rate-limiting code)",
		List.of("BasicAuth", "CORS", "RateLimit"),
		// FR-093: no JMX annotation — feature 05 owns the launcher, the DI module and the JMX
		// attributes; @JmxAttribute / JmxBean / io.activej.jmx all carry the Jmx spelling.
		"FR-093 (no JMX annotation)",
		List.of("Jmx"),
		// FR-094: no WebSocket (feature 06) and no raw TCP (feature 07) — the net package and a
		// raw ServerSocket are the only ways to start a socket of one's own here.
		"FR-094 (no WebSocket, no raw-TCP code)",
		List.of("WebSocket", "io.activej.net", "ServerSocket"),
		// FR-095: no HTTP/2- or HTTP/3-specific branch — Http2/Http3 are the identifier spellings
		// such a branch would have to use (the "HTTP/2" prose spelling is comment-only and legal).
		"FR-095 (no HTTP/2 or HTTP/3 branch)",
		List.of("Http2", "Http3"),
		// FR-096: JSON-RPC over GET is outside the parent idea — the method gate compares against
		// HttpMethod.POST only, and any GET handling would have to reference the constant.
		"FR-096 (no GET handling)",
		List.of("HttpMethod.GET")
	);

	/** One assertion per FR: none of its needles may appear on a code line of {@code src/main}. */
	@Test
	public void negativeSpaceIsAssertedPerRequirement() {
		for (Map.Entry<String, List<String>> entry : NEGATIVE_SPACE.entrySet()) {
			for (String needle : entry.getValue()) {
				List<String> violations = findOccurrenceViolations(mainRoot(), needle);
				assertTrue(entry.getKey() + " — '" + needle + "' must stay out of src/main: " + violations,
					violations.isEmpty());
			}
		}
	}

	@Test
	public void theNegativeSpaceScannerActuallyCatchesViolations() throws IOException {
		// guards the guard: every needle of every FR must be reported when it appears on a code line
		Path root = tmp.newFolder("negative-scan-negative-space").toPath();
		Path dir = root.resolve("io/activej/jsonrpc/transport/http");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("Misplaced.java"), """
			package io.activej.jsonrpc.transport.http;

			class Misplaced {
				void wrong(int x) {
					int deadline = x;
					long timeout = x;
					long Timeout = x;
					int maxInFlight = x;
					Object BasicAuth = null;
					Object CORS = null;
					Object RateLimit = null;
					Object Jmx = null;
					Object WebSocket = null;
					Object ServerSocket = null;
					Object Http2 = null;
					Object Http3 = null;
					boolean get = io.activej.http.HttpMethod.GET == null;
					io.activej.net.socket.tcp.TcpSocket raw = null;
				}
			}
			""");

		for (Map.Entry<String, List<String>> entry : NEGATIVE_SPACE.entrySet()) {
			for (String needle : entry.getValue()) {
				List<String> violations = findOccurrenceViolations(root, needle);
				assertTrue("a deliberate '" + needle + "' on a code line must be reported (" +
						   entry.getKey() + "): " + violations,
					!violations.isEmpty());
			}
		}
	}

	/**
	 * Finds {@code needle} in test sources, ignoring comment lines: the harness and this guard
	 * themselves <i>document</i> the forbidden call — prose about a rule is not a violation of it.
	 * A line-based strip is a pragmatic guard, not the enforcement of the rule; the real check is
	 * review plus the boundary test's own assertions.
	 */
	private static List<String> findOccurrenceViolations(Path root, String needle) {
		List<String> violations = new ArrayList<>();
		for (Path source : sourcesUnder(root)) {
			if (source.getFileName().toString().equals("JsonRpcHttpModuleBoundaryTest.java")) continue;
			String[] lines = read(source).split("\n");
			for (int i = 0; i < lines.length; i++) {
				String trimmed = lines[i].trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue;
				if (lines[i].contains(needle)) {
					violations.add(source + ":" + (i + 1) + " contains the forbidden text '" + needle + "'");
				}
			}
		}
		return violations;
	}

	private static List<String> findForbiddenImportViolations(Path root) {
		List<String> violations = new ArrayList<>();
		for (Path source : sourcesUnder(root)) {
			String text = read(source);
			for (String line : text.split("\n")) {
				Matcher matcher = IMPORT.matcher(line);
				if (!matcher.find()) continue;
				String imported = matcher.group(1);
				for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
					if (!(imported.equals(forbidden) || imported.startsWith(forbidden + "."))) continue;
					violations.add(source + " imports " + imported + " (forbidden prefix " + forbidden + ")");
				}
			}
		}
		return violations;
	}

	private static void writeSyntheticSource(Path root, String packageName, String className, String... imports)
		throws IOException {
		Path dir = root.resolve(packageName.replace('.', '/'));
		Files.createDirectories(dir);
		StringBuilder sb = new StringBuilder();
		sb.append("package ").append(packageName).append(";\n\n");
		for (String imp : imports) {
			sb.append("import ").append(imp).append(";\n");
		}
		sb.append("\nclass ").append(className).append(" {}\n");
		Files.writeString(dir.resolve(className + ".java"), sb.toString(), StandardCharsets.UTF_8);
	}

	private static Path mainRoot() {
		Path local = Path.of("src", "main", "java");
		if (Files.isDirectory(local)) return local;
		// running from the reactor root rather than the module basedir
		return Path.of("extra", "cloud-jsonrpc-http", "src", "main", "java");
	}

	private static Path testRoot() {
		Path local = Path.of("src", "test", "java");
		if (Files.isDirectory(local)) return local;
		// running from the reactor root rather than the module basedir
		return Path.of("extra", "cloud-jsonrpc-http", "src", "test", "java");
	}

	private static List<Path> sourcesUnder(Path root) {
		if (!Files.isDirectory(root)) return List.of();
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
