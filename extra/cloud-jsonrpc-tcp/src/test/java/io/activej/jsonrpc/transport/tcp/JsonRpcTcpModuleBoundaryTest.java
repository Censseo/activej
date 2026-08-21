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

package io.activej.jsonrpc.transport.tcp;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * The boundary guard for {@code extra/cloud-jsonrpc-tcp} (FR-005), mirroring its two siblings'
 * guards and adapted to what <i>this</i> module is allowed to reach for.
 * <p>
 * Properties are asserted by scanning the module's {@code src/main} sources as text:
 * <ul>
 *     <li>no source file imports an {@code activej-http} type — the whole point of a raw framed-TCP
 *     transport is that there is no HTTP below it: no upgrade, no per-message headers. An
 *     {@code io.activej.http} import here would mean the module had grown a second transport;</li>
 *     <li>no source file imports an {@code activej-rpc} type — feature 012's refusal of
 *     {@code io.activej.rpc} was on semantics (ADR-030), not on the module the code lived in, and
 *     survives into every transport module. The two RPC stacks stay separate by decision, and this
 *     one is the closest of the three to {@code RpcServer}'s shape, so the refusal matters most
 *     here;</li>
 *     <li>no source file imports {@code io.activej.datastream} — CSP (pull, bytes) is the
 *     <b>deliberate</b> streaming choice for a byte-framed transport (WI-4, research D3). A
 *     Datastream import would silently switch the model, so the choice is pinned rather than
 *     merely documented;</li>
 *     <li>no source file imports a {@code core-codegen} type — the transport composes
 *     {@code core-net}, {@code core-csp} and {@code activej-jsonrpc}; bytecode generation has no
 *     role here;</li>
 *     <li>no source file imports {@code org.slf4j} — every failure this module can observe already
 *     has a defined destination: the dispatcher's {@code withFailureHandler}, the calling
 *     {@code Promise}, or the reactor's fatal-error handler. A logger would be a fourth destination
 *     with no reader, and the one place peer-derived text could reach an output (D12);</li>
 *     <li>no test source mentions {@code getFreePort} — every server in this module's tests binds
 *     port {@code 0} and is asked where it landed (FR-078, ADR-028).</li>
 * </ul>
 * A plain text scan is deliberate — no bytecode or reflection scanning library exists in this
 * module's dependency set, and adding one to police a rule about dependencies would be
 * self-defeating. Each scan is paired with a <i>guard-the-guard</i> test that runs it over a
 * synthetic source tree containing the violation, so a scanner that reports zero because it is
 * broken is distinguishable from one that reports zero because the module is clean. The scans pass
 * trivially while {@code src/main} is empty; that is the point — the guard exists before the code
 * it constrains (FR-005).
 */
public class JsonRpcTcpModuleBoundaryTest {
	/** Package prefixes no source file of this module may import. */
	private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
		"io.activej.http",
		"io.activej.rpc",
		"io.activej.datastream",
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
		writeSyntheticSource(root, "io.activej.jsonrpc.transport.tcp", "Misplaced",
			"io.activej.http.HttpServer",
			"io.activej.rpc.server.RpcServer",
			"io.activej.datastream.supplier.StreamSupplier",
			"io.activej.codegen.ClassGenerator",
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
		// getFreePort() is the registry's recorded anti-pattern — an allocate-then-release race — and
		// refusing it in source is the same reasoning as the import scan: a rule about what the
		// module's tests may contain.
		List<String> violations = findOccurrenceViolations(testRoot(), "getFreePort");
		assertTrue("TestUtils.getFreePort() must stay absent from this module's src/test (FR-078, ADR-028): "
				   + violations,
			violations.isEmpty());
	}

	@Test
	public void theOccurrenceScannerActuallyCatchesAViolation() throws IOException {
		// guards the guard: the occurrence scan must report, not return zero because it is broken
		Path root = tmp.newFolder("negative-scan-getfreeport").toPath();
		Path dir = root.resolve("io/activej/jsonrpc/transport/tcp");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("Misplaced.java"), """
			package io.activej.jsonrpc.transport.tcp;

			import io.activej.test.TestUtils;

			class Misplaced {
				int port = TestUtils.getFreePort();
			}
			""");

		List<String> violations = findOccurrenceViolations(root, "getFreePort");

		assertTrue("a deliberate getFreePort call must be reported exactly once: " + violations,
			violations.size() == 1 && violations.get(0).contains("getFreePort"));
	}

	// -------------------------------------------------------------------------------------------
	// H3 — FR-024: @IgnoreLeaks MUST NOT appear anywhere in this module, main or test, without
	// exception. Unlike 015/WS (two justified core-http opt-outs), TCP has no preexisting,
	// documented platform leak at this layer to excuse — so there is nothing to allowlist here.
	// -------------------------------------------------------------------------------------------

	@Test
	public void noIgnoreLeaksAnywhereInModule() {
		// FR-024: "@IgnoreLeaks MUST NOT be used anywhere in this module" — no reservation, unlike
		// the WS sibling. The two existing hits are prose (JsonRpcTcpServerInitiatedTest /
		// JsonRpcTcpPurgeTest javadoc quoting FR-024 itself) and must NOT trip this: they are comment
		// lines, already excluded by findOccurrenceViolations' comment filter.
		List<String> violations = new ArrayList<>();
		violations.addAll(findOccurrenceViolations(mainRoot(), "@IgnoreLeaks"));
		violations.addAll(findOccurrenceViolations(testRoot(), "@IgnoreLeaks"));
		assertTrue("@IgnoreLeaks must stay absent from this module's src/main and src/test alike "
				   + "(FR-024, no exception granted in this module): " + violations,
			violations.isEmpty());
	}

	@Test
	public void theIgnoreLeaksScannerActuallyCatchesAViolation() throws IOException {
		// guards the guard: a future @IgnoreLeaks silencing a real leak must be reported, not pass
		// ByteBufRule in silence.
		Path root = tmp.newFolder("negative-scan-ignoreleaks").toPath();
		Path dir = root.resolve("io/activej/jsonrpc/transport/tcp");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("Misplaced.java"), """
			package io.activej.jsonrpc.transport.tcp;

			import io.activej.test.rules.ByteBufRule.IgnoreLeaks;

			@IgnoreLeaks("a future leak, silenced instead of fixed")
			class Misplaced {
			}
			""");

		List<String> violations = findOccurrenceViolations(root, "@IgnoreLeaks");

		assertTrue("a deliberate @IgnoreLeaks annotation must be reported: " + violations,
			violations.stream().anyMatch(v -> v.contains("@IgnoreLeaks")));
	}

	// -------------------------------------------------------------------------------------------
	// H2 — feature 09's reserved surface ({@code deadline}/{@code timeout}/{@code Timeout}/
	// {@code maxInFlight}) MUST NOT appear in src/main, with one FR-060-sanctioned exception: the
	// {@code Duration timeout} parameter of {@code JsonRpcTcpTransport.connect(...)}'s two overloads
	// (and its direct relay into {@code TcpSocket.connect(...)}), which FR-060 requires "mirroring
	// {@code TcpSocket.connect}'s own overloads". A scanner copied verbatim from 015/WS (which has
	// zero legitimate hits of its own) would be red today on this module's own sanctioned code —
	// this one carves out exactly that shape, by exact sanctioned line text, scoped to one file, and
	// nothing wider.
	// -------------------------------------------------------------------------------------------

	@Test
	public void noFeature09SurfaceInMain() {
		// This assertion is itself the proof that the carve-out does not swallow more than it
		// should: JsonRpcTcpTransport.java carries four real 'timeout' occurrences today (the
		// Duration timeout parameter of both connect(...) overloads, lines ~182/194, and its direct
		// relay into another connect(...) call, lines ~185/200) and the scan still comes back empty.
		List<String> violations = findFeature09SurfaceViolations(mainRoot());
		assertTrue("feature-09's reserved surface (deadline/timeout/Timeout/maxInFlight, section J) "
				   + "must stay out of src/main except FR-060's sanctioned connect(...) overload: "
				   + violations,
			violations.isEmpty());
	}

	@Test
	public void feature09SurfaceScannerCatchesAnOccurrencePlantedOutsideTheSanctionedOverload() throws IOException {
		// guards the guard, two ways at once: (1) a field on an unrelated class (the plan's own
		// example — a callTimeout/deadline/maxInFlight trio on something session-shaped), reported
		// because the carve-out is scoped to JsonRpcTcpTransport.java, not to any class; and (2) a
		// second, unrelated Duration parameter in a file that even shares JsonRpcTcpTransport's own
		// file name, reported because the carve-out matches the sanctioned lines' exact text, not
		// merely "some Duration timeout parameter somewhere in this file".
		Path root = tmp.newFolder("negative-scan-feature09").toPath();
		Path dir = root.resolve("io/activej/jsonrpc/transport/tcp");
		Files.createDirectories(dir);

		Files.writeString(dir.resolve("MisplacedSession.java"), """
			package io.activej.jsonrpc.transport.tcp;

			import java.time.Duration;

			class MisplacedSession {
				private Duration callTimeout;
				private long deadline;
				private int maxInFlight;
			}
			""");
		Files.writeString(dir.resolve("JsonRpcTcpTransport.java"), """
			package io.activej.jsonrpc.transport.tcp;

			import java.time.Duration;

			class JsonRpcTcpTransport {
				void schedule(Duration timeout) {
				}
			}
			""");

		List<String> violations = findFeature09SurfaceViolations(root);

		for (String token : FEATURE09_SURFACE_TOKENS) {
			assertTrue("a deliberate '" + token + "' occurrence outside the FR-060 carve-out must be "
					   + "reported: " + violations,
				violations.stream().anyMatch(v -> v.contains("'" + token + "'")));
		}
	}

	// -------------------------------------------------------------------------------------------
	// H6 — FR-003's dependency set, as pom.xml actually declares it: compile scope is exactly the
	// seven artifacts FR-003 names; test scope is the test-jar plus activej-test (FR-003's own
	// text) plus the two dependencies the pom.xml already justifies by comment for
	// TransportOverheadHarness (activej-jsonrpc-http, activej-http) — which FR-003's literal
	// "nothing else" does not cover (a documented spec/code gap, not a defect: 2026-08-20 code
	// review). Closing the set here means a ninth dependency added later, in either scope, is
	// caught rather than riding along unnoticed.
	// -------------------------------------------------------------------------------------------

	@Test
	public void pomDependencySetIsClosedByScope() throws IOException {
		String pomText = Files.readString(pomPath(), StandardCharsets.UTF_8);
		List<String> violations = findPomDependencyViolations(pomText);
		assertTrue("pom.xml's dependency set drifted from the closed set expected per scope "
				   + "(FR-003's seven compile artifacts; test scope is the test-jar + activej-test + "
				   + "the two TransportOverheadHarness deps already justified by comment): " + violations,
			violations.isEmpty());
	}

	@Test
	public void thePomDependencyScannerActuallyCatchesAnExtraDependency() {
		// guards the guard: a ninth, undeclared compile dependency must be named, not pass unnoticed.
		String pomText = """
			<project>
			  <dependencies>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-jsonrpc</artifactId></dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-net</artifactId></dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-csp</artifactId></dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-common</artifactId></dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-bytebuf</artifactId></dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-promise</artifactId></dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-eventloop</artifactId></dependency>
			    <!-- a ninth, undeclared-by-FR-003 compile dependency -->
			    <dependency><groupId>io.activej</groupId><artifactId>activej-rpc</artifactId></dependency>
			    <dependency>
			      <groupId>io.activej</groupId>
			      <artifactId>activej-jsonrpc</artifactId>
			      <type>test-jar</type>
			      <scope>test</scope>
			    </dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-test</artifactId><scope>test</scope></dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-jsonrpc-http</artifactId><scope>test</scope></dependency>
			    <dependency><groupId>io.activej</groupId><artifactId>activej-http</artifactId><scope>test</scope></dependency>
			  </dependencies>
			</project>
			""";

		List<String> violations = findPomDependencyViolations(pomText);

		assertTrue("a deliberate extra compile dependency ('activej-rpc') must be reported by name: "
				   + violations,
			violations.stream().anyMatch(v -> v.contains("activej-rpc")));
	}

	/**
	 * Finds {@code needle} in sources, ignoring comment lines: this guard itself <i>documents</i> the
	 * forbidden call — prose about a rule is not a violation of it. A line-based strip is a pragmatic
	 * guard, not the enforcement of the rule; the real check is review plus the boundary test's own
	 * assertions.
	 */
	private static List<String> findOccurrenceViolations(Path root, String needle) {
		List<String> violations = new ArrayList<>();
		for (Path source : sourcesUnder(root)) {
			if (source.getFileName().toString().equals("JsonRpcTcpModuleBoundaryTest.java")) continue;
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

	// -------------------------------------------------------------------------------------------
	// H2 support: feature 09's reserved surface, with the FR-060 carve-out.
	// -------------------------------------------------------------------------------------------

	/** feature 09's reserved surface (section J): reserved, not implemented, anywhere but FR-060. */
	private static final List<String> FEATURE09_SURFACE_TOKENS = List.of("deadline", "timeout", "Timeout", "maxInFlight");

	/** The one file FR-060's carve-out may apply to. */
	private static final String FR060_TRANSPORT_FILE = "JsonRpcTcpTransport.java";

	/**
	 * FR-060's sanctioned exception, by exact sanctioned line text rather than by a looser pattern:
	 * the {@code Duration timeout} parameter of {@code JsonRpcTcpTransport.connect(...)}'s two
	 * overloads, and its direct relay into another {@code connect(...)} call — "mirroring
	 * {@code TcpSocket.connect}'s own overloads" is FR-060's own phrase for this shape. Narrow and
	 * named on purpose: this is not "any {@code Duration timeout} parameter in this file", so a
	 * second, unrelated one in the same file is still caught (see the guard-the-guard test).
	 */
	private static final Set<String> FR060_SANCTIONED_TIMEOUT_LINES = Set.of(
		"NioReactor reactor, InetSocketAddress address, @Nullable Duration timeout,",
		"return connect(reactor, address, timeout, socketSettings, JsonRpcLimits.MAX_BODY_SIZE);",
		"return TcpSocket.connect(reactor, address, timeout, socketSettings)"
	);

	private static List<String> findFeature09SurfaceViolations(Path root) {
		List<String> violations = new ArrayList<>();
		for (Path source : sourcesUnder(root)) {
			if (source.getFileName().toString().equals("JsonRpcTcpModuleBoundaryTest.java")) continue;
			boolean sanctionedFile = source.getFileName().toString().equals(FR060_TRANSPORT_FILE);
			String[] lines = read(source).split("\n");
			for (int i = 0; i < lines.length; i++) {
				String raw = lines[i];
				String trimmed = raw.trim();
				if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue;
				for (String needle : FEATURE09_SURFACE_TOKENS) {
					if (!raw.contains(needle)) continue;
					if (needle.equals("timeout") && sanctionedFile && FR060_SANCTIONED_TIMEOUT_LINES.contains(trimmed)) {
						continue;
					}
					violations.add(source + ":" + (i + 1) + " contains the forbidden feature-09 surface text '"
									+ needle + "'");
				}
			}
		}
		return violations;
	}

	// -------------------------------------------------------------------------------------------
	// H6 support: pom.xml's dependency set, scanned as text (no XML/Maven library in this module's
	// dependency set to police its own POM with — same reasoning as the import/occurrence scanners
	// above).
	// -------------------------------------------------------------------------------------------

	private static final Set<String> FR003_COMPILE_ARTIFACT_IDS = Set.of(
		"activej-jsonrpc", "activej-net", "activej-csp", "activej-common",
		"activej-bytebuf", "activej-promise", "activej-eventloop"
	);

	private static final Set<String> TEST_SCOPE_ARTIFACT_IDS = Set.of(
		"activej-jsonrpc", "activej-test", "activej-jsonrpc-http", "activej-http"
	);

	private static final Pattern DEPENDENCY_BLOCK = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
	private static final Pattern ARTIFACT_ID_TAG = Pattern.compile("<artifactId>([^<]+)</artifactId>");
	private static final Pattern SCOPE_TAG = Pattern.compile("<scope>([^<]+)</scope>");

	private record PomDependency(String artifactId, String scope) {}

	private static List<PomDependency> parsePomDependencies(String pomText) {
		List<PomDependency> deps = new ArrayList<>();
		Matcher blocks = DEPENDENCY_BLOCK.matcher(pomText);
		while (blocks.find()) {
			String block = blocks.group(1);
			Matcher artifactId = ARTIFACT_ID_TAG.matcher(block);
			if (!artifactId.find()) continue;
			Matcher scope = SCOPE_TAG.matcher(block);
			deps.add(new PomDependency(artifactId.group(1).trim(), scope.find() ? scope.group(1).trim() : "compile"));
		}
		return deps;
	}

	private static List<String> findPomDependencyViolations(String pomText) {
		List<PomDependency> deps = parsePomDependencies(pomText);
		Set<String> compile = deps.stream()
			.filter(d -> d.scope().equals("compile"))
			.map(PomDependency::artifactId)
			.collect(Collectors.toCollection(TreeSet::new));
		Set<String> test = deps.stream()
			.filter(d -> d.scope().equals("test"))
			.map(PomDependency::artifactId)
			.collect(Collectors.toCollection(TreeSet::new));

		List<String> violations = new ArrayList<>();
		for (String extra : diff(compile, FR003_COMPILE_ARTIFACT_IDS)) {
			violations.add("compile scope declares '" + extra + "', outside FR-003's closed set " + FR003_COMPILE_ARTIFACT_IDS);
		}
		for (String missing : diff(FR003_COMPILE_ARTIFACT_IDS, compile)) {
			violations.add("compile scope is missing '" + missing + "', required by FR-003's closed set");
		}
		for (String extra : diff(test, TEST_SCOPE_ARTIFACT_IDS)) {
			violations.add("test scope declares '" + extra + "', outside the closed set " + TEST_SCOPE_ARTIFACT_IDS);
		}
		for (String missing : diff(TEST_SCOPE_ARTIFACT_IDS, test)) {
			violations.add("test scope is missing '" + missing + "', required by the closed set " + TEST_SCOPE_ARTIFACT_IDS);
		}
		return violations;
	}

	private static Set<String> diff(Set<String> a, Set<String> b) {
		Set<String> result = new TreeSet<>(a);
		result.removeAll(b);
		return result;
	}

	private static Path pomPath() {
		Path local = Path.of("pom.xml");
		if (Files.isRegularFile(local)) return local;
		// running from the reactor root rather than the module basedir
		return Path.of("extra", "cloud-jsonrpc-tcp", "pom.xml");
	}

	private static Path mainRoot() {
		Path local = Path.of("src", "main", "java");
		if (Files.isDirectory(local)) return local;
		// running from the reactor root rather than the module basedir
		return Path.of("extra", "cloud-jsonrpc-tcp", "src", "main", "java");
	}

	private static Path testRoot() {
		Path local = Path.of("src", "test", "java");
		if (Files.isDirectory(local)) return local;
		// running from the reactor root rather than the module basedir
		return Path.of("extra", "cloud-jsonrpc-tcp", "src", "test", "java");
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
