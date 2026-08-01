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

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Keeps ADR-016 (reactive shell over a synchronous protocol core) load-bearing rather than
 * decorative (FR-010): no source file under {@code io.activej.http3.frame} or
 * {@code io.activej.http3.qpack} may import {@code Reactor}, {@code Promise},
 * {@code IUdpSocket}, or anything from {@code io.activej.eventloop}/{@code io.activej.net} — those
 * two packages are this module's synchronous, eventloop-free core, proven by RFC-vector tests with
 * no {@code EventloopRule}. {@code io.activej.http3} itself (this package) is the module's only
 * reactive surface.
 * <p>
 * Written directly against the checked-out source tree, not compiled bytecode, so it fails on the
 * import line itself rather than on whatever incidental class happens to load transitively.
 */
public class Http3PackageSeamTest {
	private static final List<String> SYNCHRONOUS_PACKAGES = List.of(
		"src/main/java/io/activej/http3/frame",
		"src/main/java/io/activej/http3/qpack");

	private static final List<Pattern> FORBIDDEN_IMPORTS = List.of(
		Pattern.compile("^import\\s+(static\\s+)?io\\.activej\\.reactor\\..*;"),
		Pattern.compile("^import\\s+(static\\s+)?io\\.activej\\.promise\\..*;"),
		Pattern.compile("^import\\s+(static\\s+)?io\\.activej\\.eventloop\\..*;"),
		Pattern.compile("^import\\s+(static\\s+)?io\\.activej\\.net\\..*;"));

	@Test
	public void frameAndQpackPackagesImportNoReactiveTypes() throws IOException {
		List<String> violations = new ArrayList<>();
		for (String dir : SYNCHRONOUS_PACKAGES) {
			Path root = Path.of(dir);
			assertTrue("expected directory to exist: " + root.toAbsolutePath(), Files.isDirectory(root));
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
					for (String line : Files.readAllLines(file)) {
						String trimmed = line.strip();
						for (Pattern forbidden : FORBIDDEN_IMPORTS) {
							Matcher m = forbidden.matcher(trimmed);
							if (m.find()) {
								violations.add(file + ": " + trimmed);
							}
						}
					}
				}
			}
		}
		assertTrue(
			"ADR-016 violation(s) — frame/ and qpack/ must stay reactor/promise/net-free:\n" + String.join("\n", violations),
			violations.isEmpty());
	}
}
