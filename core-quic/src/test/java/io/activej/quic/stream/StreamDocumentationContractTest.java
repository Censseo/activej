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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T091 — <b>FR-048 and FR-049</b>, asserted rather than reviewed: every public type in this package
 * cites the RFC section it implements, every public method carries Javadoc, and every entry point that
 * takes or returns a buffer states its <b>ownership rule on itself</b>.
 *
 * <h2>Why a test rather than an audit</h2>
 * An audit is a snapshot. The two requirements it checks are exactly the kind that decay silently —
 * the next method added compiles, passes every behavioural test, and is undocumented — and a
 * buffer-carrying entry point whose ownership rule is not stated <em>at the entry point</em> is how a
 * leak or a double-recycle gets written by the next caller. So the enumeration is made against the
 * source, the same technique {@link ReactorConfinementTest} and {@code ModuleLayeringTest} already use
 * in this module.
 *
 * <h2>What is deliberately exempt, and why</h2>
 * <ul>
 *   <li>{@code toString}, {@code equals}, {@code hashCode} — they implement no RFC section, and their
 *       contract is {@code java.lang.Object}'s.</li>
 *   <li>Anything annotated {@code @Override} — Javadoc is inherited, and the interface being
 *       implemented is where the contract belongs. The two seams this matters for,
 *       {@code SendPart.Sink} and {@code ReceivePart.Listener}, document ownership on every method
 *       that carries a frame.</li>
 *   <li>Constructors of package-private helpers — they are not an API surface.</li>
 * </ul>
 * The exemptions are named here rather than expressed as a list of method names, because a list of
 * names is a list that goes stale silently.
 */
public final class StreamDocumentationContractTest {

	private static final Path MAIN_SOURCES = Path.of("src/main/java/io/activej/quic/stream");

	/** A public type or member declaration, at any nesting depth. */
	private static final Pattern PUBLIC_DECLARATION = Pattern.compile("^\\s*public\\s.*");

	/** A member declaration rather than a field: it has a parameter list. */
	private static final Pattern MEMBER = Pattern.compile("^\\s*public\\s[^=]*\\(.*");

	/** Types whose contract is {@code java.lang.Object}'s, not RFC 9000's. */
	private static final Set<String> EXEMPT_MEMBERS = Set.of("toString", "equals", "hashCode");

	/** A signature that carries buffers, directly or inside a frame — the FR-048 surface. */
	private static final Pattern CARRIES_BUFFERS = Pattern.compile(".*\\b(ByteBuf|QuicFrame|StreamFrame)\\b.*");

	/**
	 * Any of these in a method's own Javadoc counts as stating the ownership rule. Several spellings,
	 * because the rules genuinely differ per entry point — "takes ownership", "borrowed", "the caller
	 * owns and must recycle" — and forcing one sentence would make the documentation worse, not the
	 * check stronger.
	 */
	private static final List<String> OWNERSHIP_WORDS =
		List.of("ownership", "owns", "owned", "borrow", "recycle", "release");

	private static final Pattern RFC_CITATION = Pattern.compile(".*RFC 900[0-9].*");

	private record Source(String fileName, List<String> lines) {}

	private static List<Source> mainSources() throws IOException {
		assertTrue("the main sources are not where this test expects them: " + MAIN_SOURCES.toAbsolutePath(),
			Files.isDirectory(MAIN_SOURCES));
		List<Source> sources = new ArrayList<>();
		try (Stream<Path> files = Files.list(MAIN_SOURCES)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
				sources.add(new Source(file.getFileName().toString(),
					List.of(Files.readString(file, StandardCharsets.UTF_8).split("\n", -1))));
			}
		}
		assertFalse("no source files were scanned; the test would pass vacuously", sources.isEmpty());
		return sources;
	}

	/** The Javadoc block immediately above {@code index}, or {@code null} if there is none. */
	private static String javadocAbove(List<String> lines, int index) {
		int end = index - 1;
		while (end >= 0 && lines.get(end).strip().startsWith("@")) end--;
		if (end < 0 || !lines.get(end).strip().endsWith("*/")) return null;
		int start = end;
		while (start >= 0 && !lines.get(start).strip().startsWith("/**")) start--;
		if (start < 0) return null;
		return String.join(" ", lines.subList(start, end + 1));
	}

	private static boolean isOverride(List<String> lines, int index) {
		for (int i = Math.max(0, index - 3); i < index; i++) {
			if (lines.get(i).strip().equals("@Override")) return true;
		}
		return false;
	}

	private static String memberName(String declaration) {
		int paren = declaration.indexOf('(');
		if (paren < 0) return "";
		String head = declaration.substring(0, paren).strip();
		int space = head.lastIndexOf(' ');
		return space < 0 ? head : head.substring(space + 1);
	}

	// ---------------------------------------------------------------- FR-049: the RFC citation

	@Test
	public void everyPublicTypeCitesTheRfcSectionItImplements() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Source source : mainSources()) {
			if (source.fileName().equals("package-info.java")) continue;
			String whole = String.join(" ", source.lines());
			if (!RFC_CITATION.matcher(whole).matches()) {
				offenders.add(source.fileName());
			}
		}
		assertTrue("FR-049: these types cite no RFC 9000/9001/9002 section anywhere: " + offenders,
			offenders.isEmpty());
	}

	@Test
	public void thePackageInfoCitesTheRfcSectionsThisPackageImplements() throws IOException {
		String packageInfo = mainSources().stream()
			.filter(s -> s.fileName().equals("package-info.java"))
			.findFirst()
			.map(s -> String.join(" ", s.lines()))
			.orElseThrow(() -> new AssertionError("the package has no package-info.java"));

		assertTrue("FR-049", RFC_CITATION.matcher(packageInfo).matches());
	}

	// ---------------------------------------------------------------- FR-049: Javadoc on every method

	@Test
	public void everyPublicMethodCarriesJavadoc() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Source source : mainSources()) {
			List<String> lines = source.lines();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (!PUBLIC_DECLARATION.matcher(line).matches()) continue;
				if (!MEMBER.matcher(line).matches()) continue;
				String name = memberName(line);
				if (EXEMPT_MEMBERS.contains(name)) continue;
				if (isOverride(lines, i)) continue;
				if (javadocAbove(lines, i) == null) {
					offenders.add(source.fileName() + ':' + (i + 1) + ' ' + line.strip());
				}
			}
		}
		assertTrue("FR-049: these public methods carry no Javadoc:\n  " + String.join("\n  ", offenders),
			offenders.isEmpty());
	}

	// ---------------------------------------------------------------- FR-048: ownership on the entry point

	@Test
	public void everyBufferCarryingEntryPointStatesItsOwnershipRuleOnItself() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Source source : mainSources()) {
			List<String> lines = source.lines();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (!PUBLIC_DECLARATION.matcher(line).matches()) continue;
				if (!MEMBER.matcher(line).matches()) continue;
				if (!CARRIES_BUFFERS.matcher(line).matches()) continue;
				if (EXEMPT_MEMBERS.contains(memberName(line))) continue;
				if (isOverride(lines, i)) continue;
				String javadoc = javadocAbove(lines, i);
				String lower = javadoc == null ? "" : javadoc.toLowerCase();
				if (OWNERSHIP_WORDS.stream().noneMatch(lower::contains)) {
					offenders.add(source.fileName() + ':' + (i + 1) + ' ' + line.strip());
				}
			}
		}
		assertTrue("FR-048: these entry points take or return a buffer without stating who owns it:\n  " +
				   String.join("\n  ", offenders),
			offenders.isEmpty());
	}

	/**
	 * The FR-048 scan above is only worth anything if it actually finds the entry points it is meant to
	 * police — a regex that matched nothing would pass silently. These four are the ownership rules the
	 * contract's summary table names, one of each shape.
	 */
	@Test
	public void theOwnershipScanReallyReachesTheEntryPointsItIsMeantTo() throws IOException {
		List<String> reached = new ArrayList<>();
		for (Source source : mainSources()) {
			List<String> lines = source.lines();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (!PUBLIC_DECLARATION.matcher(line).matches()) continue;
				if (!MEMBER.matcher(line).matches()) continue;
				if (!CARRIES_BUFFERS.matcher(line).matches()) continue;
				if (isOverride(lines, i)) continue;
				reached.add(source.fileName() + '#' + memberName(line));
			}
		}
		assertTrue(reached.toString(), reached.contains("QuicStream.java#reader"));
		assertTrue(reached.toString(), reached.contains("QuicStream.java#writer"));
		assertTrue(reached.toString(), reached.contains("StreamReassembler.java#add"));
		assertTrue(reached.toString(), reached.contains("ReceivePart.java#onStreamFrame"));
	}
}
