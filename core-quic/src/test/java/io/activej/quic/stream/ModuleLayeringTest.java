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

package io.activej.quic.stream;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T024 — the <b>import fence</b> (checklist remediation R-1, CHK025/CHK041).
 * <p>
 * Feature 04 gives {@code core-quic} its first dependency on {@code activej-csp}, and CSP is a
 * <i>reactive</i> abstraction. ADR-016's whole value is that {@code codec/}, {@code crypto/} and
 * {@code tls/} stay synchronous and eventloop-free, so their RFC-vector tests need no reactor; the
 * {@code connection/} package owns the reactor but must not grow a channel API either, because the
 * stream layer is what bridges the two. None of that is expressible in the compiler — a single
 * {@code import io.activej.csp.…} in the wrong package collapses the seam silently and nothing fails.
 * Hence a source scan, in the package that introduced the risk.
 * <p>
 * The second rule restates the module's oldest guard rail: QUIC varints are a different wire format
 * from the serializer's, and {@code core-serializer}/{@code core-codegen} would drag ASM into a module
 * that needs neither.
 * <p>
 * Scanning is textual and deliberately crude — the failure message names the file and the offending
 * line, which is all a future reviewer needs. It covers every line of code rather than only
 * {@code import} statements, since a fully-qualified reference needs no import and is exactly how a
 * future violation would slip past an import-only scan. Source paths are resolved relative to the
 * module directory, which is Surefire's working directory, exactly as {@code QuicTimerDeterminismTest}
 * does.
 */
public final class ModuleLayeringTest {

	private static final Path MAIN_SOURCES = Path.of("src/main/java/io/activej/quic");

	/** The three synchronous packages plus the reactive one, none of which may know about CSP. */
	private static final List<String> CSP_FREE_PACKAGES = List.of("codec", "crypto", "tls", "connection");

	private static final String CSP_PACKAGE = "io.activej.csp";
	private static final List<String> BYTECODE_PACKAGES = List.of("io.activej.serializer", "io.activej.codegen");

	@Test
	public void noSynchronousOrConnectionPackageImportsCsp() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (String pkg : CSP_FREE_PACKAGES) {
			Path directory = MAIN_SOURCES.resolve(pkg);
			assertTrue("the source tree moved: " + directory.toAbsolutePath(), Files.isDirectory(directory));
			collectImportOffenders(directory, List.of(CSP_PACKAGE), offenders);
		}
		assertEquals(
			"ADR-016: codec/, crypto/, tls/ and connection/ must not import a CSP type — the stream layer " +
			"is the only bridge between the synchronous protocol core and a channel API",
			List.of(), offenders);
	}

	@Test
	public void noQuicSourceImportsTheSerializerOrCodegen() throws IOException {
		assertTrue("the source tree moved: " + MAIN_SOURCES.toAbsolutePath(), Files.isDirectory(MAIN_SOURCES));

		List<String> offenders = new ArrayList<>();
		collectImportOffenders(MAIN_SOURCES, BYTECODE_PACKAGES, offenders);
		assertEquals(
			"core-quic must not depend on core-serializer or core-codegen: QUIC varints are a different " +
			"wire format, and it would drag ASM into a module that needs neither",
			List.of(), offenders);
	}

	/**
	 * Appends {@code "<file>:<line number> references <forbidden>: <the line>"} for every line of code
	 * under {@code directory} naming one of {@code forbiddenPackages}.
	 * <p>
	 * Not only {@code import} statements: a fully-qualified {@code io.activej.csp.ChannelSupplier} used
	 * inline needs no import and would walk straight through an import-only scan, which is the obvious
	 * way to end up past this fence without meaning to. Comment-only lines are the one place a forbidden
	 * package may legitimately appear — a {@code @link} from the layer that <i>is</i> allowed to use it,
	 * or a note explaining this very rule — so those, and only those, are skipped.
	 */
	private static void collectImportOffenders(Path directory, List<String> forbiddenPackages, List<String> offenders)
		throws IOException {
		try (Stream<Path> sources = Files.walk(directory)) {
			for (Path source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
				List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i).strip();
					if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) continue;
					for (String forbidden : forbiddenPackages) {
						// The trailing dot keeps a hypothetical io.activej.cspx from being a false positive.
						if (line.contains(forbidden + ".")) {
							offenders.add(source + ":" + (i + 1) + " references " + forbidden + ": " + line);
						}
					}
				}
			}
		}
	}
}
