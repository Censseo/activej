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

package io.activej.jsonrpc.transport.ws;

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
 * The boundary guard for {@code extra/cloud-jsonrpc-ws} (FR-005).
 * <p>
 * Properties are asserted by scanning the module's {@code src/main} sources as text:
 * <ul>
 *     <li>no source file imports an {@code activej-rpc} type — feature 012's refusal of
 *     {@code io.activej.rpc} was on semantics (ADR-030), not on the module the code lived in, and
 *     survives the move into this transport module;</li>
 *     <li>no source file imports a {@code core-codegen} type — the transport is built on
 *     {@code core-http} and {@code activej-jsonrpc}, and codegen has no role here;</li>
 *     <li>no source file imports {@code org.slf4j} — every failure this module can observe already
 *     has a defined destination: the dispatcher's {@code withFailureHandler}, the calling
 *     {@code Promise}, or the reactor's fatal-error handler. A logger would be a fourth destination
 *     with no reader (FR-006);</li>
 *     <li>no test source mentions {@code getFreePort} — every server in this module's tests binds
 *     port {@code 0} and is asked where it landed (FR-078, ADR-028).</li>
 *     <li>the <b>negative space</b>: the requirements that say MUST NOT need a check, not silence —
 *     no per-call deadline/timeout setting (feature 09), no in-flight concurrency bound (feature 09),
 *     no auth/CORS/rate-limiting code, no JMX annotation, no frame-level read/write API (FR-011's
 *     message-level discipline), and no {@code GET} handling. Each is asserted as the absence of the
 *     code tokens such an implementation could not avoid, one test per needle, on code lines only —
 *     documented prose about a rule is not a violation of it, so the Javadoc that <i>states</i> the
 *     negative space stays legal while the code that would <i>fill</i> it fails the build.</li>
 * </ul>
 * A plain text scan is deliberate — no bytecode or reflection scanning library exists in this
 * module's dependency set, and adding one to police a rule about dependencies would be
 * self-defeating. The test passes trivially while {@code src/main} is empty; that is the point —
 * it exists before the code it constrains (FR-005).
 */
public class JsonRpcWsModuleBoundaryTest {
	/** Package prefixes no source file of this module may import. */
	private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
		"io.activej.rpc",
		"io.activej.codegen",
		"io.activej.csp",
		"io.activej.net",
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
		writeSyntheticSource(root, "io.activej.jsonrpc.transport.ws", "Misplaced",
			"io.activej.rpc.RpcClient",
			"io.activej.codegen.ClassGenerator",
			"io.activej.csp.supplier.ChannelSupplier",
			"io.activej.net.socket.tcp.TcpSocket",
			"org.slf4j.Logger");

		List<String> violations = findForbiddenImportViolations(root);

		// every forbidden prefix must be reported — one per (prefix, import) pair
		for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
			assertTrue("a deliberate '" + prefix + "' import must be reported: " + violations,
				violations.stream().anyMatch(v -> v.contains("forbidden prefix " + prefix)));
		}
	}

	@Test
	public void noGetFreePortInTests() {
		// FR-078, ADR-028: every server in this module's tests binds :0 and is asked where it landed.
		// getFreePort() is the registry's recorded anti-pattern; refusing it in source is the same
		// reasoning as the import scan — a rule about what the module's tests may contain.
		List<String> violations = findOccurrenceViolations(testRoot(), "getFreePort");
		assertTrue("TestUtils.getFreePort() must stay absent from this module's src/test (FR-078, ADR-028): "
				   + violations,
			violations.isEmpty());
	}

	@Test
	public void theOccurrenceScannerActuallyCatchesAViolation() throws IOException {
		// guards the guard: the occurrence scan must report, not return zero because it is broken
		Path root = tmp.newFolder("negative-scan-getfreeport").toPath();
		Path dir = root.resolve("io/activej/jsonrpc/transport/ws");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("Misplaced.java"), """
			package io.activej.jsonrpc.transport.ws;

			import io.activej.test.TestUtils;

			class Misplaced {
				int port = TestUtils.getFreePort();
			}
			""");

		List<String> violations = findOccurrenceViolations(root, "getFreePort");

		assertTrue("a deliberate getFreePort call must be reported exactly once: " + violations,
			violations.size() == 1 && violations.get(0).contains("getFreePort"));
	}

	// ---------------------------------------------------------------------------------------------------
	// The negative space — feature-09, auth/JMX, frame-level and GET refusals as asserted source scans.
	// A deferred requirement with an owner is still a requirement: an unchecked one silently acquires
	// an implementation. Each needle is a code token such an implementation could not avoid; comment
	// lines are skipped (the module's own Javadoc documents these refusals), and a needle that ever
	// appears on a code line fails its test by name.
	// ---------------------------------------------------------------------------------------------------

	private static final Map<String, List<String>> NEGATIVE_SPACE = Map.of(
		// feature 09: a per-call JSON-RPC deadline and a timeout setting are feature 09's, not this
		// module's. "timeout" and "Timeout" together cover a lowercase field and every
		// TimeoutException/READ_WRITE_TIMEOUT/withTimeout spelling; the inherited connection-level
		// bound (HttpServer.readWriteTimeout) is core-http's, and may be named in prose only.
		"feature-09 (no per-call deadline, no timeout setting)",
		List.of("deadline", "timeout", "Timeout"),
		// feature 09: an in-flight concurrency bound is feature 09's (maxInFlight is its name on
		// PendingCall); the session's inFlightCount is an observed value, not a bound, and the
		// needle deliberately does not match it.
		"feature-09 (no in-flight concurrency bound)",
		List.of("maxInFlight"),
		// no auth, CORS or rate-limiting code — these compose through AsyncServlet decorators
		// (BasicAuthServlet, ServletWithStats), none is implemented here.
		"no auth, CORS or rate-limiting code",
		List.of("BasicAuth", "CORS", "RateLimit"),
		// no JMX annotation — feature 05 owns the launcher, the DI module and the JMX attributes;
		// @JmxAttribute / JmxBean / io.activej.jmx all carry the Jmx spelling.
		"no JMX annotation",
		List.of("Jmx"),
		// FR-011: the transport speaks the message-level API (readMessage/writeMessage) and never
		// the frame-level one (readFrame/writeFrame). Frame-level writes are how BINARY/empty
		// documents could sneak past the message-level refusals; a code line that names a frame
		// method is a violation of the discipline itself.
		"FR-011 (no frame-level read/write API)",
		List.of("readFrame", "writeFrame"),
		// no GET handling — the upgrade gate compares against the WebSocket handshake only; any
		// GET handling would have to reference the constant.
		"no GET handling",
		List.of("HttpMethod.GET")
	);

	/** One assertion per needle: none may appear on a code line of {@code src/main}. */
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
		Path dir = root.resolve("io/activej/jsonrpc/transport/ws");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("Misplaced.java"), """
			package io.activej.jsonrpc.transport.ws;

			import io.activej.http.HttpMethod;
			import io.activej.http.IWebSocket.Frame;

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
					Frame frame = null;
					Object readFrame = frame;
					Object writeFrame = frame;
					boolean get = HttpMethod.GET == null;
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
	 * Finds {@code needle} in sources, ignoring comment lines: the harness and this guard themselves
	 * <i>document</i> the forbidden call — prose about a rule is not a violation of it. A line-based
	 * strip is a pragmatic guard, not the enforcement of the rule; the real check is review plus the
	 * boundary test's own assertions.
	 */
	private static List<String> findOccurrenceViolations(Path root, String needle) {
		List<String> violations = new ArrayList<>();
		for (Path source : sourcesUnder(root)) {
			if (source.getFileName().toString().equals("JsonRpcWsModuleBoundaryTest.java")) continue;
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
		return Path.of("extra", "cloud-jsonrpc-ws", "src", "main", "java");
	}

	private static Path testRoot() {
		Path local = Path.of("src", "test", "java");
		if (Files.isDirectory(local)) return local;
		// running from the reactor root rather than the module basedir
		return Path.of("extra", "cloud-jsonrpc-ws", "src", "test", "java");
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