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

package io.activej.jsonrpc.service;

import io.activej.common.builder.AbstractBuilder;
import io.activej.json.JsonCodec;
import io.activej.jsonrpc.JsonRpcRequest;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * SC-003, as a compile-time asset rather than a sentence: renaming a method of a {@code @JsonRpcService}
 * interface breaks compilation on <b>both</b> the implementing side and the calling side.
 *
 * <h2>Why this is a test and not a paragraph</h2>
 * The property being demonstrated is a <i>negative</i> — that certain source does <b>not</b> compile — and a
 * negative cannot be shown by code that is in the build, because code in the build compiles by definition.
 * The repository has no "must not compile" convention to follow (nothing else in it invokes
 * {@code javax.tools}), so this class establishes the lightest one that is still mechanical: it drives the
 * JDK's own compiler over three throwaway sources and asserts what each one does.
 * <ol>
 *     <li>the unmodified interface, implemented and called — <b>compiles</b>, which is what makes the other
 *     two mean something;</li>
 *     <li>the same, with the <i>implementation</i> renamed — <b>fails</b>, twice: the class no longer
 *     implements the abstract method, and the renamed one overrides nothing;</li>
 *     <li>the same, with the <i>call site</i> renamed — <b>fails</b>: there is no such method.</li>
 * </ol>
 * No new dependency: {@link ToolProvider} is part of the JDK, and this is test scope.
 *
 * <h2>What it does and does not prove</h2>
 * It proves that this feature adds <b>no mechanism that weakens</b> the ordinary Java guarantee — the
 * interface method is a plain method signature, and both sides of it are checked by the compiler, exactly as
 * they would be without any annotation. It does <b>not</b> prove anything about the <i>wire</i> name, and the
 * distinction is the whole point: a method relying on {@code @JsonRpcMethod}'s empty-{@code value()} fallback
 * has its Java name on the wire, and renaming it changes the wire while every one of these three compilations
 * still succeeds. That is why the README states the wire-name commitment where a consumer reads it, and why
 * an explicit {@code @JsonRpcMethod("…")} value is the mitigation.
 */
public class JsonRpcRenameCompilationTest {
	private static final String PACKAGE = "io.activej.jsonrpc.service.renameprobe";

	/**
	 * The unmodified worked example: {@link UserApi} implemented and called. Both halves are here so that one
	 * source file exercises both sides of the property.
	 */
	private static final String INTACT = """
		package %s;

		import io.activej.jsonrpc.service.fixtures.User;
		import io.activej.jsonrpc.service.fixtures.UserApi;
		import io.activej.promise.Promise;

		public class Probe {
			public static class Implementation implements UserApi {
				@Override
				public Promise<User> getUser(long id) { return Promise.of(new User(id, "u")); }

				@Override
				public void touch(long id) { }
			}

			public static Promise<User> caller(UserApi api) { return api.getUser(42); }
		}
		""".formatted(PACKAGE);

	/** The implementing side renamed, and nothing else. */
	private static final String IMPLEMENTATION_RENAMED = INTACT.replace(
		"public Promise<User> getUser(long id)", "public Promise<User> fetchUser(long id)");

	/** The calling side renamed, and nothing else. */
	private static final String CALL_SITE_RENAMED = INTACT.replace("api.getUser(42)", "api.fetchUser(42)");

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void theUnmodifiedInterfaceCompiles() {
		List<String> diagnostics = compile(INTACT);

		assertTrue("the unmodified worked example must compile — if it does not, this test's classpath is " +
				   "wrong and the two negative cases below prove nothing:\n\t" + String.join("\n\t", diagnostics),
			diagnostics.isEmpty());
	}

	@Test
	public void renamingTheImplementationBreaksCompilation() {
		List<String> diagnostics = compile(IMPLEMENTATION_RENAMED);

		assertFalse("renaming the implementing method must not compile", diagnostics.isEmpty());
		String rendered = String.join("\n", diagnostics);
		assertTrue("the implementation no longer implements the interface method: " + rendered,
			rendered.contains("getUser"));
		assertTrue("and the renamed method overrides nothing: " + rendered,
			rendered.contains("fetchUser") || rendered.contains("does not override"));
	}

	@Test
	public void renamingTheCallSiteBreaksCompilation() {
		List<String> diagnostics = compile(CALL_SITE_RENAMED);

		assertFalse("renaming the called method must not compile", diagnostics.isEmpty());
		assertTrue("the call site names a method that does not exist: " + diagnostics,
			String.join("\n", diagnostics).contains("fetchUser"));
	}

	// ---------------------------------------------------------------------------------------------------
	// Driving the JDK compiler.
	// ---------------------------------------------------------------------------------------------------

	/** @return every {@code ERROR} diagnostic, rendered; empty means the source compiled */
	private List<String> compile(String source) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		// null only on a JRE with no compiler, which no Maven build runs on; reported as skipped, not passed
		assumeTrue("no javax.tools compiler is available in this JVM", compiler != null);

		try {
			Path sourceDir = tmp.newFolder().toPath();
			Path file = sourceDir.resolve(PACKAGE.replace('.', '/')).resolve("Probe.java");
			Files.createDirectories(file.getParent());
			Files.writeString(file, source, UTF_8);
			Path classesDir = tmp.newFolder().toPath();

			DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
			try (StandardJavaFileManager files = compiler.getStandardFileManager(collected, Locale.ROOT, UTF_8)) {
				Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(file.toFile());
				List<String> options = List.of(
					"-classpath", classpath(),
					"-d", classesDir.toString(),
					"-proc:none",
					"-nowarn");
				compiler.getTask(null, files, collected, options, null, units).call();
			}

			List<String> errors = new ArrayList<>();
			for (Diagnostic<? extends JavaFileObject> diagnostic : collected.getDiagnostics()) {
				if (diagnostic.getKind() != Diagnostic.Kind.ERROR) continue;
				errors.add(diagnostic.getMessage(Locale.ROOT).replace('\n', ' '));
			}
			return errors;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The classpath the probe needs, assembled from the code source of one class per artefact rather than from
	 * {@code java.class.path} — Surefire's booter puts a manifest-only jar there, and a probe compiled against
	 * that would fail for a reason having nothing to do with the property under test.
	 */
	private static String classpath() {
		Set<String> entries = new LinkedHashSet<>();
		for (Class<?> marker : List.of(
			UserApi.class,              // the module's test classes: the interface and its DTO
			JsonRpcRequest.class,       // the module's own classes
			JsonRpcTransport.class,
			Promise.class,              // activej-promise
			Reactor.class,              // activej-eventloop
			AbstractBuilder.class,      // activej-common
			JsonCodec.class,            // activej-json
			Nullable.class)             // org.jetbrains:annotations
		) {
			String location = locationOf(marker);
			if (location != null) entries.add(location);
		}
		return String.join(File.pathSeparator, entries);
	}

	private static @Nullable String locationOf(Class<?> type) {
		CodeSource codeSource = type.getProtectionDomain().getCodeSource();
		if (codeSource == null || codeSource.getLocation() == null) return null;
		try {
			return Path.of(codeSource.getLocation().toURI()).toString();
		} catch (URISyntaxException e) {
			return null;
		}
	}
}
