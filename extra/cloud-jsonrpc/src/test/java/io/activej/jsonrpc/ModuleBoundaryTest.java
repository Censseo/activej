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

package io.activej.jsonrpc;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The boundary guard for {@code extra/cloud-jsonrpc}.
 * <p>
 * Properties are asserted by scanning a module's {@code src/main} sources as text:
 * <ol>
 *     <li><b>FR-004 / FR-007 / FR-085 / SC-005</b> — no source file imports a reactor, {@code Promise},
 *     {@code ByteBuf}, transport, codegen, {@code activej-rpc} or {@code slf4j} type. Reading the POM alone
 *     would miss any of these: {@code slf4j-api} is a <i>global</i> compile dependency declared in the root
 *     {@code pom.xml}, and {@code activej-common} can reach further types transitively.</li>
 *     <li><b>FR-108 / FR-109</b> — the reactor and {@code Promise} prefixes are <i>package-scoped</i>
 *     rather than module-wide: they are permitted inside {@code io.activej.jsonrpc.service} and
 *     {@code io.activej.jsonrpc.transport} (and their subpackages), but stay rejected everywhere else in
 *     this module, including {@code io.activej.jsonrpc} and {@code io.activej.jsonrpc.impl}. The other
 *     seven forbidden prefixes remain module-wide.</li>
 *     <li><b>FR-042</b> — the {@code io.activej.jmx} and {@code io.activej.common.inspector} prefixes
 *     are permitted in {@code io.activej.jsonrpc.service} (and its subpackages) only: the service layer
 *     is where per-method observability lives, and the transport SPI must not carry it.</li>
 *     <li><b>FR-022</b> — no field (and no {@code record} component) is declared as a
 *     {@code com.dslplatform.json.JsonReader}. A retained reader is the obvious first implementation of a
 *     deferred payload and it is wrong: dsl-json's index space does not survive a buffer refill, so the
 *     captured offsets stop meaning anything the moment the reader moves on.</li>
 * </ol>
 * A plain text scan is deliberate — no bytecode or reflection scanning library exists in this module's
 * dependency set, and adding one to police a rule about dependencies would be self-defeating.
 */
public class ModuleBoundaryTest {
	/** Package prefixes no source file of this module may import. */
	private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
		"io.activej.reactor",
		"io.activej.promise",
		"io.activej.bytebuf",
		"io.activej.http",
		"io.activej.net",
		"io.activej.csp",
		"io.activej.codegen",
		"io.activej.rpc",
		"org.slf4j",
		"io.activej.jmx",
		"io.activej.common.inspector"
	);

	/**
	 * The two prefixes this feature confines to {@link #PERMITTED_PACKAGES} instead of forbidding
	 * module-wide (FR-108). Every other entry in {@link #FORBIDDEN_IMPORT_PREFIXES} stays module-wide
	 * (FR-109).
	 */
	private static final Set<String> PACKAGE_SCOPED_PREFIXES = Set.of("io.activej.reactor", "io.activej.promise");

	/** Packages (and subpackages) where {@link #PACKAGE_SCOPED_PREFIXES} are permitted. */
	private static final Set<String> PERMITTED_PACKAGES = Set.of(
		"io.activej.jsonrpc.service",
		"io.activej.jsonrpc.transport"
	);

	/**
	 * The prefixes confined to a single package instead of being forbidden module-wide, and the packages
	 * that may import them. Unlike {@link #PACKAGE_SCOPED_PREFIXES} — which the service <i>and</i>
	 * transport packages share — JMX and the inspector are permitted in the service package only
	 * (FR-042): the transport is a transport-agnostic SPI and must not carry observability types.
	 */
	private static final Map<String, Set<String>> PACKAGE_SCOPED_PREFIXES_BY_PACKAGE = Map.of(
		"io.activej.jmx", Set.of("io.activej.jsonrpc.service"),
		"io.activej.common.inspector", Set.of("io.activej.jsonrpc.service")
	);

	private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$]+)\\s*;");

	/**
	 * A field declaration of type {@code JsonReader}. At least one access/storage modifier is required so a
	 * <i>local</i> {@code JsonReader} inside a method body — which is entirely legitimate, the decoder drives
	 * one — is not mistaken for retained state.
	 */
	private static final Pattern JSON_READER_FIELD = Pattern.compile(
		"^\\s*(?:@\\w+\\s+)*(?:(?:public|protected|private|static|volatile|transient)\\s+)+(?:final\\s+)?" +
		"(?:com\\.dslplatform\\.json\\.)?JsonReader\\s*(?:<[^>]*>)?\\s+\\w+\\s*[;=]",
		Pattern.MULTILINE);

	/** The component list of a {@code record} declaration. */
	private static final Pattern RECORD_HEADER = Pattern.compile("\\brecord\\s+\\w+\\s*(?:<[^>]*>)?\\s*\\(([^)]*)\\)",
		Pattern.DOTALL);

	private static final Pattern JSON_READER_TYPE = Pattern.compile("\\bJsonReader\\b");

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void noForbiddenImport() {
		List<String> violations = findForbiddenImportViolations(mainRoot());
		if (!violations.isEmpty()) {
			fail("this module must stay free of the reactor, transport, codegen and logging stacks:\n\t" +
				 String.join("\n\t", violations));
		}
	}

	@Test
	public void reactorAndPromisePermittedOnlyInServiceAndTransportPackages() throws IOException {
		Path root = tmp.newFolder("permitted-scan").toPath();
		writeSyntheticSource(root, "io.activej.jsonrpc.service", "InService",
			"io.activej.promise.Promise", "io.activej.reactor.Reactor");
		writeSyntheticSource(root, "io.activej.jsonrpc.transport", "InTransport",
			"io.activej.promise.Promise", "io.activej.reactor.Reactor");
		writeSyntheticSource(root, "io.activej.jsonrpc.service.impl", "InServiceImpl",
			"io.activej.promise.Promise");
		writeSyntheticSource(root, "io.activej.jsonrpc", "InEnvelope",
			"io.activej.promise.Promise", "io.activej.reactor.Reactor");
		writeSyntheticSource(root, "io.activej.jsonrpc.impl", "InEnvelopeImpl",
			"io.activej.promise.Promise", "io.activej.reactor.Reactor");

		List<String> violations = findForbiddenImportViolations(root);

		for (String violation : violations) {
			assertTrue("io.activej.jsonrpc.service and .transport must never be flagged for reactor/promise, " +
					   "but got: " + violation,
				!violation.contains("InService.java") && !violation.contains("InTransport.java") &&
				!violation.contains("InServiceImpl.java"));
		}
		assertEquals("io.activej.jsonrpc and io.activej.jsonrpc.impl must still reject both scoped prefixes " +
					 "(2 violations each): " + violations,
			4, violations.stream()
				.filter(v -> v.contains("InEnvelope.java") || v.contains("InEnvelopeImpl.java"))
				.count());
	}

	@Test
	public void misplacedPromiseImportInEnvelopePackageIsRejected() throws IOException {
		// SC-011: the guard must be shown to actually catch a violation, not merely report zero every time.
		Path root = tmp.newFolder("negative-scan").toPath();
		writeSyntheticSource(root, "io.activej.jsonrpc", "Misplaced", "io.activej.promise.Promise");

		List<String> violations = findForbiddenImportViolations(root);

		assertEquals("a deliberately misplaced import must be reported exactly once: " + violations,
			1, violations.size());
		assertTrue(violations.get(0).contains("io.activej.promise.Promise"));
	}

	@Test
	public void jmxAndInspectorPermittedOnlyInServicePackage() throws IOException {
		// FR-042: the JMX annotations and the inspector base types are allowed in io.activej.jsonrpc.service
		// (and its subpackages) and nowhere else — the transport SPI in particular must not carry them.
		Path root = tmp.newFolder("jmx-scan").toPath();
		writeSyntheticSource(root, "io.activej.jsonrpc.service", "InService",
			"io.activej.jmx.api.JmxAttribute", "io.activej.common.inspector.BaseInspector");
		writeSyntheticSource(root, "io.activej.jsonrpc.service.impl", "InServiceImpl",
			"io.activej.common.inspector.AbstractInspector");
		writeSyntheticSource(root, "io.activej.jsonrpc.transport", "InTransport",
			"io.activej.jmx.api.JmxAttribute");
		writeSyntheticSource(root, "io.activej.jsonrpc", "InEnvelope",
			"io.activej.common.inspector.BaseInspector", "io.activej.jmx.api.JmxAttribute");
		writeSyntheticSource(root, "io.activej.jsonrpc.impl", "InEnvelopeImpl",
			"io.activej.jmx.api.JmxAttribute");

		List<String> violations = findForbiddenImportViolations(root);

		for (String violation : violations) {
			assertTrue("io.activej.jsonrpc.service (and its subpackages) must never be flagged for " +
					   "jmx/inspector, but got: " + violation,
				!violation.contains("InService.java") && !violation.contains("InServiceImpl.java"));
		}
		assertEquals("io.activej.jsonrpc.transport, .jsonrpc and .jsonrpc.impl must each reject the " +
					 "scoped prefixes (1, 2 and 1 violations respectively): " + violations,
			4, violations.size());
	}

	@Test
	public void noRetainedJsonReader() {
		List<String> violations = new ArrayList<>();
		for (Path source : sourcesUnder(mainRoot())) {
			String text = read(source);

			Matcher field = JSON_READER_FIELD.matcher(text);
			while (field.find()) {
				violations.add(source + " declares a JsonReader field: " + field.group().strip());
			}

			Matcher record = RECORD_HEADER.matcher(text);
			while (record.find()) {
				if (JSON_READER_TYPE.matcher(record.group(1)).find()) {
					violations.add(source + " declares a JsonReader record component: " + record.group().strip());
				}
			}
		}
		if (!violations.isEmpty()) {
			fail("FR-022: no type of this module may hold a JsonReader — the reader's index space does not " +
				 "survive a buffer refill, so a captured [start, end) pair outlives its meaning:\n\t" +
				 String.join("\n\t", violations));
		}
	}

	@Test
	public void theScannerActuallySeesSomething() {
		// guards the guard: a scan that silently found no files would pass both tests above forever
		assertTrue("src/main/java was not found from " + Path.of("").toAbsolutePath(), Files.isDirectory(mainRoot()));
	}

	private static List<String> findForbiddenImportViolations(Path root) {
		List<String> violations = new ArrayList<>();
		for (Path source : sourcesUnder(root)) {
			boolean scopePermitted = isPermittedPackage(packageOf(root, source), PERMITTED_PACKAGES);
			String pkg = packageOf(root, source);
			String text = read(source);
			for (String line : text.split("\n")) {
				Matcher matcher = IMPORT.matcher(line);
				if (!matcher.find()) continue;
				String imported = matcher.group(1);
				for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
					if (!(imported.equals(forbidden) || imported.startsWith(forbidden + "."))) continue;
					if (scopePermitted && PACKAGE_SCOPED_PREFIXES.contains(forbidden)) continue;
					Set<String> permitted = PACKAGE_SCOPED_PREFIXES_BY_PACKAGE.get(forbidden);
					if (permitted != null && isPermittedPackage(pkg, permitted)) continue;
					violations.add(source + " imports " + imported + " (forbidden prefix " + forbidden + ")");
				}
			}
		}
		return violations;
	}

	private static boolean isPermittedPackage(String pkg, Set<String> permittedPackages) {
		for (String permitted : permittedPackages) {
			if (pkg.equals(permitted) || pkg.startsWith(permitted + ".")) return true;
		}
		return false;
	}

	private static String packageOf(Path root, Path source) {
		Path relativeDir = root.relativize(source.getParent());
		StringBuilder sb = new StringBuilder();
		for (Path part : relativeDir) {
			if (!sb.isEmpty()) sb.append('.');
			sb.append(part);
		}
		return sb.toString();
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
		return Path.of("extra", "cloud-jsonrpc", "src", "main", "java");
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
