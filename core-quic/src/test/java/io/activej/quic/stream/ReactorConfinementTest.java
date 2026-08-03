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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T098 — FR-040 / WI-1, asserted rather than reviewed: <b>every public method of
 * {@link QuicStreamManager} and {@link QuicStream} opens with {@code checkInReactorThread(this)}</b>,
 * and <b>no source in this package reads the system clock</b>.
 *
 * <h2>Why reflection <i>and</i> a source scan</h2>
 * Neither half works alone. Reflection enumerates the methods, so a method added tomorrow is covered
 * by this test the moment it compiles — a hand-written list of names is a list that goes stale
 * silently, which is the exact failure mode this guard rail exists to prevent. But reflection cannot
 * see a method's <i>first statement</i>, and "the guard is present somewhere in the body" is not the
 * contract: a guard after the first field read has already lost the race it exists to prevent. So the
 * enumeration comes from the class and the assertion is made against the source, which is also the
 * technique {@code ModuleLayeringTest} and {@code QuicTimerDeterminismTest} already use in this module.
 *
 * <h2>The clock half</h2>
 * Feature 03's guard rail, restated for the package that inherited it: RTT, loss detection and every
 * timer read {@code reactor.currentTimeMillis()}, so a test can drive time with
 * {@code TestCurrentTimeProvider}. A direct {@code System.nanoTime()} anywhere under it makes a timer
 * assertion flaky by construction. This package owns no timer of its own today, which is precisely why
 * the rule is worth pinning down before one appears.
 *
 * @see io.activej.reactor.Reactive#checkInReactorThread(io.activej.reactor.Reactive)
 */
public final class ReactorConfinementTest {

	private static final Path MAIN_SOURCES = Path.of("src/main/java/io/activej/quic/stream");

	private static final String GUARD = "checkInReactorThread(this);";

	/**
	 * The methods that must <b>not</b> be guarded, each for a stated reason.
	 * <p>
	 * {@code toString} is a diagnostic: it is called from a debugger, from a log appender and from
	 * an assertion message, none of which run on the reactor thread, and a guard there would turn a
	 * diagnostic into a second failure. {@code equals}/{@code hashCode} are the same case — and are
	 * identity-based here in any event.
	 */
	private static final Set<String> UNGUARDED_BY_DESIGN = Set.of("toString", "equals", "hashCode");

	/** The forbidden constructs: time comes from the reactor, never from the JVM (feature 03, Q5). */
	private static final List<String> FORBIDDEN_CLOCK_READS =
		List.of("System.currentTimeMillis()", "System.nanoTime()");

	// ---------------------------------------------------------------- FR-040: the guard

	@Test
	public void everyPublicMethodOfTheStreamManagerOpensWithTheReactorGuard() throws IOException {
		assertGuardedThroughout(QuicStreamManager.class);
	}

	@Test
	public void everyPublicMethodOfAStreamOpensWithTheReactorGuard() throws IOException {
		assertGuardedThroughout(QuicStream.class);
	}

	@Test
	public void theTwoReactiveTypesActuallyDeclarePublicMethodsToCheck() {
		// A guard test whose enumeration silently returns nothing passes for the wrong reason. These two
		// counts are deliberately loose lower bounds: they exist to catch an empty enumeration, not to be
		// updated whenever a counter is added.
		assertTrue("QuicStreamManager should expose the frame-handler seam plus its counters",
			methodsRequiringTheGuard(QuicStreamManager.class).size() >= 10);
		assertTrue("QuicStream should expose its identity, its two halves and its two aborts",
			methodsRequiringTheGuard(QuicStream.class).size() >= 10);
	}

	@Test
	public void theSourceScanSeesAOneLineMethodBody() {
		// The blind spot this scan used to have. With the body opened and closed on the declaration line,
		// searching forward for the next line ending in '{' lands in the *following* method and reports
		// that one's first statement — so an unguarded one-liner would pass on its neighbour's behalf.
		// Asserted from a synthetic source because no such method exists in the package today, which is
		// precisely why the gap could sit here unnoticed.
		List<String> source = List.of(
			"	public void unguarded() { doSomething(); }",
			"",
			"	public void guarded() { checkInReactorThread(this); doSomething(); }",
			"",
			"	public void alsoGuarded() {",
			"		checkInReactorThread(this);",
			"	}");

		assertEquals("doSomething();", firstStatementAfter(source, 0));
		assertEquals(GUARD, firstStatementAfter(source, 2));
		assertEquals(GUARD, firstStatementAfter(source, 4));
	}

	/**
	 * Every public instance method the class declares must have {@link #GUARD} as the first statement
	 * of its body.
	 */
	private static void assertGuardedThroughout(Class<?> type) throws IOException {
		List<String> source = sourceOf(type);
		List<String> offenders = new ArrayList<>();
		for (String name : methodsRequiringTheGuard(type)) {
			List<Integer> declarations = declarationsOf(source, name);
			if (declarations.isEmpty()) {
				offenders.add(name + ": no declaration found in " + type.getSimpleName() + ".java — the" +
							  " scan below cannot have checked it");
				continue;
			}
			for (int line : declarations) {
				String first = firstStatementAfter(source, line);
				if (!GUARD.equals(first)) {
					offenders.add(type.getSimpleName() + ".java:" + (line + 1) + ' ' + name +
								  " opens with \"" + first + "\" rather than \"" + GUARD + '"');
				}
			}
		}
		assertEquals("FR-040 / WI-1: every public method of a reactive component opens with " + GUARD,
			List.of(), offenders);
	}

	/** The public, non-static methods the class itself declares, minus the diagnostics. */
	private static Set<String> methodsRequiringTheGuard(Class<?> type) {
		Set<String> names = new TreeSet<>();
		for (Method method : type.getDeclaredMethods()) {
			if (method.isSynthetic() || method.isBridge()) continue;
			int modifiers = method.getModifiers();
			if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) continue;
			if (UNGUARDED_BY_DESIGN.contains(method.getName())) continue;
			names.add(method.getName());
		}
		return names;
	}

	/**
	 * The indices of the lines declaring a public method called {@code name} — plural, because an
	 * overload set must be guarded in full, and reflection reports one name for all of it.
	 */
	private static List<Integer> declarationsOf(List<String> source, String name) {
		List<Integer> lines = new ArrayList<>();
		for (int i = 0; i < source.size(); i++) {
			String line = source.get(i).strip();
			if (!line.startsWith("public ")) continue;
			int parenthesis = line.indexOf('(');
			if (parenthesis < 0) continue;
			// The declared name is the identifier immediately before the parameter list, which also
			// excludes a call to the same name appearing inside some other public method's signature.
			int start = parenthesis;
			while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
				start--;
			}
			if (line.substring(start, parenthesis).equals(name)) {
				lines.add(i);
			}
		}
		return lines;
	}

	/**
	 * The first statement of the body opened at or after {@code declarationLine}.
	 * <p>
	 * The body may open <i>on</i> the declaration line, and the whole method may live there —
	 * {@code public void foo() { checkInReactorThread(this); bar(); }}, which the code style here
	 * explicitly permits. Scanning forward for the next line that <i>ends</i> with {@code '{'} would walk
	 * straight past such a method and report the <i>next</i> method's first statement, so a one-line
	 * method with no guard at all would pass on its neighbour's behalf. Hence the inline case first.
	 */
	private static String firstStatementAfter(List<String> source, int declarationLine) {
		String declaration = source.get(declarationLine).strip();
		int brace = declaration.indexOf('{');
		if (brace >= 0) {
			String inline = declaration.substring(brace + 1).strip();
			int semicolon = inline.indexOf(';');
			if (semicolon >= 0) return inline.substring(0, semicolon + 1);
			if (!inline.isEmpty()) return inline;   // an empty body, or an expression-bodied oddity
		}

		int i = declarationLine;
		while (i < source.size() && !source.get(i).strip().endsWith("{")) {
			i++;
		}
		for (i = i + 1; i < source.size(); i++) {
			String line = source.get(i).strip();
			if (line.isEmpty() || line.startsWith("//")) continue;
			return line;
		}
		return "<end of file>";
	}

	private static List<String> sourceOf(Class<?> type) throws IOException {
		Path source = MAIN_SOURCES.resolve(type.getSimpleName() + ".java");
		assertTrue("the source tree moved: " + source.toAbsolutePath(), Files.isRegularFile(source));
		return Files.readAllLines(source, StandardCharsets.UTF_8);
	}

	// ---------------------------------------------------------------- FR-040: the clock

	@Test
	public void noSourceInThisPackageReadsTheSystemClock() throws IOException {
		assertTrue("the source tree moved: " + MAIN_SOURCES.toAbsolutePath(),
			Files.isDirectory(MAIN_SOURCES));

		List<String> offenders = new ArrayList<>();
		try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
			for (Path source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
				List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);
					for (String forbidden : FORBIDDEN_CLOCK_READS) {
						if (line.contains(forbidden)) {
							offenders.add(source.getFileName() + ":" + (i + 1) + " uses " + forbidden);
						}
					}
				}
			}
		}
		assertEquals("time comes from reactor.currentTimeMillis() only, so TestCurrentTimeProvider stays" +
					 " authoritative (feature 003 clarification Q5)", List.of(), offenders);
	}

	@Test
	public void theClockScanActuallyLooksAtFiles() throws IOException {
		// The negative assertion above passes vacuously over an empty file set; this is what makes it mean
		// something.
		try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
			long count = sources.filter(path -> path.toString().endsWith(".java")).count();
			assertFalse("the stream package should hold well over a dozen sources, found " + count,
				count < 10);
		}
	}
}
