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

package io.activej.jsonrpc.transport.http.interop;

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
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovery of an external {@code curl} and bounded invocation of it — the two halves of the live
 * interoperability tier (T051, T052 — FR-063…FR-063c, ADR-027). Local to this module by contract:
 * no dependency on {@code core-http3}'s test artifacts; the ADR-027 rules are re-stated here.
 * Test-scope by contract: this is harness, not API.
 * <p>
 * <b>Discovery</b> (FR-063a): the binary comes from the documented system property
 * {@value #CURL_PROPERTY}, defaulting to {@code curl} on {@code PATH}; capability is probed with
 * {@code curl --version} once per JVM and cached, requiring the {@code http} protocol in the
 * tool's <b>own reported feature list</b> — never inferred from the binary's presence, so a binary
 * of the right name without the capability cannot read as a pass over the wrong behaviour.
 * <p>
 * <b>Absence skips; misconfiguration fails</b> (FR-063b): an <b>unset</b> property with no usable
 * client on {@code PATH} is {@link Outcome#ABSENT} — an environment fact, the caller skips with a
 * stated reason and starts no server; an <b>explicitly configured</b> path that does not work —
 * missing, not executable, or lacking the {@code http} capability — is {@link Outcome#MISCONFIGURED}
 * — a mistake, and the caller fails.
 * <p>
 * <b>Invocation</b> (FR-063c): every run starts from an <b>argument vector — no shell</b> — is
 * bounded by {@value #TIMEOUT_PROPERTY} seconds (default {@value #DEFAULT_TIMEOUT_SECONDS}) and
 * destroyed forcibly on expiry; stdout, stderr and the exit status are captured; the response body
 * goes to a temp file reduced to length + SHA-256 digest and the file is deleted in the same
 * {@code finally} that reaps the process, so no peer byte ever reaches a field that could enter an
 * assertion message. Status and HTTP version come from {@code -w '%{http_version}'}/
 * {@code '%{http_code}'}.
 */
public final class CurlProbe {
	/** The operator-facing path property (FR-063a); may name a wrapper script. */
	public static final String CURL_PROPERTY = "activej.jsonrpc.interop.curl";
	/** The per-invocation timeout property, seconds (FR-063c). */
	public static final String TIMEOUT_PROPERTY = "activej.jsonrpc.interop.timeoutSeconds";
	public static final long DEFAULT_TIMEOUT_SECONDS = 30;
	/** How long the captured-output readers may still be blocked after the process died. */
	private static final long READER_JOIN_TIMEOUT_MILLIS = 2_000;

	/** Resolved once per JVM, like an {@code ApplicationSettings} tunable — never mutated afterwards. */
	public static final long TIMEOUT_SECONDS =
		Long.parseLong(System.getProperty(TIMEOUT_PROPERTY, String.valueOf(DEFAULT_TIMEOUT_SECONDS)));

	private static final String WRITE_OUT = "\nVER=%{http_version} ST=%{http_code}\n";
	private static final Pattern VER_ST = Pattern.compile("VER=(\\S+) ST=(\\d+)");
	private static final Pattern PROTOCOLS_LINE = Pattern.compile("(?m)^Protocols:.*$");

	private static volatile @Nullable Result cached;

	private CurlProbe() {}

	public enum Outcome {
		/** Run the case. */
		USABLE,
		/** No usable client — the caller {@code Assume}s and no server is started (FR-063b). */
		ABSENT,
		/** An explicitly configured client that cannot work — a hard failure (FR-063b). */
		MISCONFIGURED
	}

	/** The discovery outcome. */
	public static final class Result {
		public final Outcome outcome;
		/** The resolved path actually probed. */
		public final String executable;
		/** The first line of {@code curl --version}, for the skip/failure message. */
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

	/** One bounded external run and everything observed about it. */
	public static final class Invocation {
		public final int exitCode;
		/** Captured in full; reaches failure messages. */
		public final String stdout;
		/** Captured in full; reaches failure messages. */
		public final String stderr;
		/** From {@code -w '%{http_version}'}; the caller may assert it. */
		public final String httpVersion;
		/** From {@code -w '%{http_code}'}; {@code -1} when not parseable. */
		public final int statusCode;
		/** Bytes of the response body. */
		public final long bodyLength;
		/** SHA-256 hex of the response body; the only form a body ever takes in a message (FR-063c). */
		public final String bodyDigest;
		/** The bound expired and the process was destroyed forcibly (FR-063c). */
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
	 * The cached, property-driven probe: {@value #CURL_PROPERTY} (or {@code curl} on {@code PATH}),
	 * run once per JVM.
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
	 * Probes one binary — uncached, so the caller can cover every outcome in one JVM.
	 *
	 * @param configured whether the path came from an explicit {@value #CURL_PROPERTY} setting;
	 *                    only a configured-but-unusable path is {@link Outcome#MISCONFIGURED}
	 */
	public static Result probe(String path, boolean configured) {
		ProcessOutcome run = runProcess(List.of(path, "--version"), TIMEOUT_SECONDS);
		if (run.ioFailure) {
			return new Result(
				configured ? Outcome.MISCONFIGURED : Outcome.ABSENT,
				path, "",
				configured ?
					"cannot execute '" + path + "' configured via " + CURL_PROPERTY :
					"no curl found: cannot execute '" + path + "'");
		}
		String versionLine = firstLine(run.stdout);
		if (run.exitCode != 0) {
			return new Result(
				configured ? Outcome.MISCONFIGURED : Outcome.ABSENT,
				path, versionLine,
				"'curl --version' exited " + run.exitCode + (configured ? " (configured via " + CURL_PROPERTY + ")" : ""));
		}
		// FR-063a: the capability comes from curl's OWN reported feature list — the Protocols line
		// of its --version output — never from the binary's presence.
		boolean hasHttp = hasProtocol(run.stdout, "http");
		if (hasHttp) {
			return new Result(Outcome.USABLE, path, versionLine, "");
		}
		String reason = "no 'http' protocol in the version output of '" + path + "'" +
			(configured ? " configured via " + CURL_PROPERTY : "");
		// FR-063b: a configured path that does not work is a mistake; an unset property with an
		// incapable PATH client is an environment fact.
		return new Result(configured ? Outcome.MISCONFIGURED : Outcome.ABSENT, path, versionLine, reason);
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
			bodyFile = Files.createTempFile("activej-jsonrpc-interop-", ".body");
			List<String> command = new ArrayList<>(arguments);
			command.add("-o");
			command.add(bodyFile.toString());
			command.add("-w");
			command.add(WRITE_OUT);

			ProcessOutcome run = runProcess(command, timeoutSeconds);

			// The body is reduced to (length, digest) and the temp file is deleted in the finally
			// below — the bytes themselves never enter a field that reaches an assertion message (FR-063c).
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

	/** Whether the {@code Protocols:} line of a {@code curl --version} output lists {@code protocol}. */
	static boolean hasProtocol(String versionOutput, String protocol) {
		Matcher matcher = PROTOCOLS_LINE.matcher(versionOutput);
		if (!matcher.find()) return false;
		for (String token : matcher.group().substring("Protocols:".length()).trim().split("\\s+")) {
			if (token.toLowerCase(Locale.ROOT).equals(protocol)) return true;
		}
		return false;
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
	 * so far is still attached (FR-063c).
	 */
	private static ProcessOutcome runProcess(List<String> command, long timeoutSeconds) {
		try {
			Process process = new ProcessBuilder(command).start();
			StreamReader stdout = new StreamReader(process.getInputStream());
			StreamReader stderr = new StreamReader(process.getErrorStream());
			Thread stdoutThread = new Thread(stdout, "jsonrpc-interop-stdout-reader");
			Thread stderrThread = new Thread(stderr, "jsonrpc-interop-stderr-reader");
			stdoutThread.setDaemon(true);
			stderrThread.setDaemon(true);
			stdoutThread.start();
			stderrThread.start();

			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				process.waitFor();
			}
			// destroyForcibly() kills only the direct child; a wrapper script whose real client is a
			// grandchild inheriting the output pipes keeps them open past the process's death, and
			// the readers' readAllBytes() would then block forever. Bound the joins and, on expiry,
			// close the pipes from this side so the readers fail out with what they captured — the
			// reader threads are daemons, so nothing else is left to hang.
			try {
				stdoutThread.join(READER_JOIN_TIMEOUT_MILLIS);
				if (stdoutThread.isAlive()) process.getInputStream().close();
				stderrThread.join(READER_JOIN_TIMEOUT_MILLIS);
				if (stderrThread.isAlive()) process.getErrorStream().close();
			} catch (IOException e) {
				// Closing a dead process's streams can fail; the readers are daemons — nothing to do.
			}

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
