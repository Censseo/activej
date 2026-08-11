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

import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The boundary guard for {@code extra/cloud-jsonrpc}.
 * <p>
 * Two properties are asserted by scanning this module's own {@code src/main} sources as text:
 * <ol>
 *     <li><b>FR-004 / FR-007 / FR-085 / SC-005</b> — no source file imports a reactor, {@code Promise},
 *     {@code ByteBuf}, transport, codegen, {@code activej-rpc} or {@code slf4j} type. Reading the POM alone
 *     would miss any of these: {@code slf4j-api} is a <i>global</i> compile dependency declared in the root
 *     {@code pom.xml}, and {@code activej-common} can reach further types transitively.</li>
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
		"org.slf4j"
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

	@Test
	public void noForbiddenImport() {
		List<String> violations = new ArrayList<>();
		for (Path source : mainSources()) {
			String text = read(source);
			for (String line : text.split("\n")) {
				Matcher matcher = IMPORT.matcher(line);
				if (!matcher.find()) continue;
				String imported = matcher.group(1);
				for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
					if (imported.equals(forbidden) || imported.startsWith(forbidden + ".")) {
						violations.add(source + " imports " + imported + " (forbidden prefix " + forbidden + ")");
					}
				}
			}
		}
		if (!violations.isEmpty()) {
			fail("this module must stay free of the reactor, transport, codegen and logging stacks:\n\t" +
				 String.join("\n\t", violations));
		}
	}

	@Test
	public void noRetainedJsonReader() {
		List<String> violations = new ArrayList<>();
		for (Path source : mainSources()) {
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

	private static Path mainRoot() {
		Path local = Path.of("src", "main", "java");
		if (Files.isDirectory(local)) return local;
		// running from the reactor root rather than the module basedir
		return Path.of("extra", "cloud-jsonrpc", "src", "main", "java");
	}

	private static List<Path> mainSources() {
		Path root = mainRoot();
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
