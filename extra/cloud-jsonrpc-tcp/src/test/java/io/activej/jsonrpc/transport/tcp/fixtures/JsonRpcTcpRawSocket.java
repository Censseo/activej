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

package io.activej.jsonrpc.transport.tcp.fixtures;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A blocking, dependency-free peer for the framing and hostile-input tests: a plain
 * {@link Socket} that writes <b>arbitrary</b> bytes and reads back <b>one LF-terminated line</b>.
 * The `printf | nc` one-liner of {@code quickstart.md} §2, expressed in Java so a test can assert
 * on it.
 * <p>
 * It speaks no JSON-RPC and does no framing of its own — {@link #write(byte[])} sends exactly the
 * bytes given (so a test can send a bare {@code \n}, a CRLF-terminated document, a never-terminated
 * stream, or one byte at a time), and {@link #readLine()} consumes bytes up to the first {@code \n}
 * and returns them <i>without</i> the terminator, or {@code null} at end-of-stream — which is how a
 * test observes "the server closed the connection" as distinct from "the server answered".
 * <p>
 * Blocking by design and therefore <b>never</b> called on a reactor thread: the pattern is the
 * {@code core-http} one this module's HTTP sibling also uses — run the eventloop on its own thread,
 * drive the socket from the JUnit thread. Every read is bounded by the socket's {@code SO_TIMEOUT}
 * so a server that never answers fails the test quickly instead of hanging the suite.
 * <p>
 * This class binds nothing of its own — the server under test binds port {@code 0} and is asked
 * where it landed (ADR-028) — so it stays invisible to the module's {@code getFreePort} scan.
 */
public final class JsonRpcTcpRawSocket implements AutoCloseable {
	/** The default read bound: a server that owes an answer must produce it well inside this. */
	public static final int DEFAULT_SO_TIMEOUT_MILLIS = 5_000;

	private final Socket socket;
	private final OutputStream output;
	private final InputStream input;

	private JsonRpcTcpRawSocket(Socket socket) throws IOException {
		this.socket = socket;
		this.output = socket.getOutputStream();
		this.input = socket.getInputStream();
	}

	/** Connects to {@code localhost:port} with the default read bound. */
	public static JsonRpcTcpRawSocket connect(int port) throws IOException {
		return connect(new InetSocketAddress("localhost", port), DEFAULT_SO_TIMEOUT_MILLIS);
	}

	/** Connects to {@code address} with the default read bound. */
	public static JsonRpcTcpRawSocket connect(InetSocketAddress address) throws IOException {
		return connect(address, DEFAULT_SO_TIMEOUT_MILLIS);
	}

	/**
	 * Connects to {@code address}, bounding every subsequent read by {@code soTimeoutMillis}. A test
	 * asserting that <i>no</i> answer arrives (a notification, a silently-dropped document) passes a
	 * short bound and expects {@link java.net.SocketTimeoutException}.
	 */
	public static JsonRpcTcpRawSocket connect(InetSocketAddress address, int soTimeoutMillis) throws IOException {
		Socket socket = new Socket();
		try {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(soTimeoutMillis);
			socket.connect(address, soTimeoutMillis);
		} catch (IOException | RuntimeException e) {
			socket.close();
			throw e;
		}
		return new JsonRpcTcpRawSocket(socket);
	}

	/** Writes exactly these bytes and flushes — no terminator is appended. */
	public void write(byte[] bytes) throws IOException {
		output.write(bytes);
		output.flush();
	}

	/** Writes {@code text} as UTF-8 and flushes — no terminator is appended. */
	public void write(String text) throws IOException {
		write(text.getBytes(UTF_8));
	}

	/** Writes {@code document} as UTF-8 followed by a single {@code \n} — one framed message. */
	public void writeLine(String document) throws IOException {
		write(document + "\n");
	}

	/**
	 * Reads up to and including the first {@code \n} and returns the bytes before it, decoded as
	 * UTF-8; returns {@code null} if end-of-stream is reached first (the peer closed, with or
	 * without a partial line).
	 */
	public @Nullable String readLine() throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int b;
		while ((b = input.read()) != -1) {
			if (b == '\n') return line.toString(UTF_8);
			line.write(b);
		}
		return null;
	}

	/** Half-closes the write direction — the peer sees end-of-stream on its read side. */
	public void shutdownOutput() throws IOException {
		socket.shutdownOutput();
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}
}
