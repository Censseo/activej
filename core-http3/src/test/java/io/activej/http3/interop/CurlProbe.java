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

package io.activej.http3.interop;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovery of an external HTTP/3 client (T007) and bounded invocation of it (T008) — the two halves
 * the automated interop suite drives curl through. Test-scope by contract: this is harness, not API.
 * <p>
 * <b>Discovery</b> (contracts §6, FR-002…FR-005): the binary comes from {@value #CURL_PROPERTY},
 * defaulting to {@code curl} on {@code PATH}; capability is probed with {@code curl -V} once per JVM
 * and cached, requiring {@code HTTP3} in the reported features. An unset property with an absent or
 * incapable client is {@link Outcome#ABSENT} — an assumption, never a failure; an explicitly
 * configured but missing or unexecutable path is {@link Outcome#MISCONFIGURED} — a mistake, not an
 * absence; an explicitly configured path that runs but reports no {@code HTTP3} is
 * {@link Outcome#ABSENT} with a reason naming the missing feature. {@link #probe(String, boolean)}
 * is the uncached core; {@link #result()} adds the once-per-JVM cache and the property plumbing.
 * <p>
 * <b>Invocation</b> (FR-008, FR-013): the process is started from an <b>argument vector — no
 * shell</b> — bounded by {@value #TIMEOUT_PROPERTY} seconds (default {@value #DEFAULT_TIMEOUT_SECONDS}),
 * destroyed forcibly on expiry. The response body goes to a temp file reduced to length + SHA-256
 * digest, and the file is deleted in the same {@code finally} that reaps the process, so no body byte
 * ever reaches a field that could enter an assertion message. Version and status come from
 * {@code -w '%{http_version}'}/{@code '%{http_code}'}; version {@code 3} is asserted by the caller on
 * every case (FR-007).
 */
public final class CurlProbe {
	/** The operator-facing path property (contracts §6). May name a wrapper script. */
	public static final String CURL_PROPERTY = "activej.interop.curl";
	/** The per-invocation timeout property, seconds (contracts §6). */
	public static final String TIMEOUT_PROPERTY = "activej.interop.timeoutSeconds";
	public static final long DEFAULT_TIMEOUT_SECONDS = 30;

	/** Resolved once per JVM, like an {@code ApplicationSettings} tunable — never mutated afterwards. */
	public static final long TIMEOUT_SECONDS =
		Long.parseLong(System.getProperty(TIMEOUT_PROPERTY, String.valueOf(DEFAULT_TIMEOUT_SECONDS)));

	private static final String WRITE_OUT = "\nVER=%{http_version} ST=%{http_code}\n";
	private static final Pattern VER_ST = Pattern.compile("VER=(\\S+) ST=(\\d+)");

	private static volatile @Nullable Result cached;

	private CurlProbe() {}

	public enum Outcome {
		/** Run the case. */
		USABLE,
		/** No usable client — the caller {@code Assume}s and no server is started (FR-004). */
		ABSENT,
		/** An explicitly configured client that cannot work — a hard failure (FR-005). */
		MISCONFIGURED
	}

	/** The discovery outcome (data-model: {@code CurlProbe.Result}). */
	public static final class Result {
		public final Outcome outcome;
		/** The resolved path actually probed. */
		public final String executable;
		/** The first line of {@code curl -V}, for the skip/failure message. */
		public final String versionLine;
		/** Why it is not {@link Outcome#USABLE}; empty when it is. */
		public final String reason;

		Result(Outcome outcome, String executable, String versionLine, String reason) {
			this.outcome = outcome;
			this.executable = executable;
			this.versionLine = versionLine;
			this.reason = reason;
		}

		@Override
		public String toString() {
			return "CurlProbe.Result{outcome=" + outcome +
				", executable='" + executable + '\'' +
				", versionLine='" + versionLine + '\'' +
				(reason.isEmpty() ? "" : ", reason='" + reason + '\'') +
				'}';
		}
	}

	/** One bounded external run and everything observed about it (data-model: {@code CurlProbe.Invocation}). */
	public static final class Invocation {
		public final int exitCode;
		/** Captured in full; reaches failure messages. */
		public final String stdout;
		/** Captured in full; reaches failure messages. */
		public final String stderr;
		/** From {@code -w '%{http_version}'}; the caller asserts {@code "3"} (FR-007). */
		public final String httpVersion;
		/** From {@code -w '%{http_code}'}; {@code -1} when not parseable. */
		public final int statusCode;
		/** Bytes of the response body. */
		public final long bodyLength;
		/** SHA-256 hex of the response body; the only form a body ever takes in a message (FR-013). */
		public final String bodyDigest;
		/** The bound expired and the process was destroyed forcibly (FR-008). */
		public final boolean timedOut;

		Invocation(
			int exitCode, String stdout, String stderr, String httpVersion, int statusCode,
			long bodyLength, String bodyDigest, boolean timedOut
		) {
			this.exitCode = exitCode;
			this.stdout = stdout;
			this.stderr = stderr;
			this.httpVersion = httpVersion;
			this.statusCode = statusCode;
			this.bodyLength = bodyLength;
			this.bodyDigest = bodyDigest;
			this.timedOut = timedOut;
		}
	}

	/**
	 * The cached, property-driven probe: {@code activej.interop.curl} (or {@code curl} on
	 * {@code PATH}), run once per JVM.
	 */
	public static Result result() {
		Result result = cached;
		if (result == null) {
			synchronized (CurlProbe.class) {
				result = cached;
				if (result == null) {
					String configured = System.getProperty(CURL_PROPERTY);
					result = probe(configured != null ? configured : "curl", configured != null);
					cached = result;
				}
			}
		}
		return result;
	}

	/**
	 * Probes one binary — uncached, so the test can cover all four outcomes in one JVM.
	 *
	 * @param configured whether the path came from an explicit {@value #CURL_PROPERTY} setting; only a
	 *                    configured-but-unusable path is {@link Outcome#MISCONFIGURED}
	 */
	public static Result probe(String path, boolean configured) {
		ProcessOutcome run = runProcess(List.of(path, "-V"), TIMEOUT_SECONDS);
		if (run.ioFailure) {
			return new Result(
				configured ? Outcome.MISCONFIGURED : Outcome.ABSENT,
				path, "",
				configured ?
					"cannot execute '" + path + "' configured via " + CURL_PROPERTY :
					"no HTTP/3 client found: cannot execute '" + path + "'");
		}
		String versionLine = firstLine(run.stdout);
		boolean usable = run.exitCode == 0 && run.stdout.contains("HTTP3");
		if (usable) {
			return new Result(Outcome.USABLE, path, versionLine, "");
		}
		String reason = "no HTTP3 feature in the version output of '" + path + "'" +
			(configured ? " configured via " + CURL_PROPERTY : "") +
			(run.exitCode != 0 ? " (exit code " + run.exitCode + ")" : "");
		return new Result(Outcome.ABSENT, path, versionLine, reason);
	}

	/**
	 * One bounded run of an external client. The argument vector is {@code arguments} verbatim —
	 * binary first, no shell — with {@code -o <temp body file> -w '…'} appended, so every caller gets
	 * the write-out and the body reduction for free and cannot omit them.
	 */
	public static Invocation invoke(List<String> arguments) {
		return invoke(arguments, TIMEOUT_SECONDS);
	}

	/** {@link #invoke(List)} with an explicit bound, for the timeout test. */
	public static Invocation invoke(List<String> arguments, long timeoutSeconds) {
		Path bodyFile = null;
		try {
			bodyFile = Files.createTempFile("activej-interop-", ".body");
			List<String> command = new ArrayList<>(arguments);
			command.add("-o");
			command.add(bodyFile.toString());
			command.add("-w");
			command.add(WRITE_OUT);

			ProcessOutcome run = runProcess(command, timeoutSeconds);

			// The body is reduced to (length, digest) and the temp file is deleted in the finally
			// below — the bytes themselves never enter a field that reaches an assertion message (FR-013).
			long bodyLength = 0;
			String bodyDigest;
			MessageDigest digest = sha256();
			try (InputStream in = Files.newInputStream(bodyFile)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = in.read(buffer)) >= 0) {
					digest.update(buffer, 0, read);
					bodyLength += read;
				}
			}
			bodyDigest = HexFormat.of().formatHex(digest.digest());

			Matcher matcher = VER_ST.matcher(run.stdout);
			String httpVersion = "";
			int statusCode = -1;
			while (matcher.find()) {
				httpVersion = matcher.group(1);
				statusCode = Integer.parseInt(matcher.group(2));
			}

			return new Invocation(
				run.exitCode, run.stdout, run.stderr, httpVersion, statusCode, bodyLength, bodyDigest, run.timedOut);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to create or read the interop body temp file", e);
		} finally {
			if (bodyFile != null) {
				try {
					Files.deleteIfExists(bodyFile);
				} catch (IOException ignored) {
					// Best effort; a leftover temp file must not mask the invocation result.
				}
			}
		}
	}

	/** {@code MessageDigest} for the body reduction; SHA-256 is guaranteed present on any JVM. */
	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 is always available", e);
		}
	}

	private static String firstLine(String text) {
		int newline = text.indexOf('\n');
		return newline < 0 ? text : text.substring(0, newline);
	}

	private static final class ProcessOutcome {
		final boolean ioFailure;
		final int exitCode;
		final String stdout;
		final String stderr;
		final boolean timedOut;

		ProcessOutcome(boolean ioFailure, int exitCode, String stdout, String stderr, boolean timedOut) {
			this.ioFailure = ioFailure;
			this.exitCode = exitCode;
			this.stdout = stdout;
			this.stderr = stderr;
			this.timedOut = timedOut;
		}
	}

	/**
	 * Starts, bounds and reaps one process. {@code ioFailure} separates "could not even start" (the
	 * missing/not-executable case of the discovery contract) from every outcome an executed binary
	 * can produce. On expiry the process is destroyed forcibly and reaped, and whatever was captured
	 * so far is still attached (FR-008).
	 */
	private static ProcessOutcome runProcess(List<String> command, long timeoutSeconds) {
		try {
			Process process = new ProcessBuilder(command).start();
			StreamReader stdout = new StreamReader(process.getInputStream());
			StreamReader stderr = new StreamReader(process.getErrorStream());
			Thread stdoutThread = new Thread(stdout, "interop-stdout-reader");
			Thread stderrThread = new Thread(stderr, "interop-stderr-reader");
			stdoutThread.setDaemon(true);
			stderrThread.setDaemon(true);
			stdoutThread.start();
			stderrThread.start();

			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				process.waitFor();
			}
			stdoutThread.join();
			stderrThread.join();

			return new ProcessOutcome(false, process.exitValue(), stdout.text, stderr.text, !finished);
		} catch (IOException e) {
			return new ProcessOutcome(true, -1, "", "", false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while running " + command.get(0), e);
		}
	}

	private static final class StreamReader implements Runnable {
		private final InputStream in;
		String text = "";

		StreamReader(InputStream in) {
			this.in = in;
		}

		@Override
		public void run() {
			try {
				text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException ignored) {
				// A stream that dies with the process is expected on the timeout path; keep what was read.
			}
		}
	}
}
