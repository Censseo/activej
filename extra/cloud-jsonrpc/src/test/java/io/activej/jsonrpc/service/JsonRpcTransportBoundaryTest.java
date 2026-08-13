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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FR-084: the transport SPI names <b>no transport-specific type</b> — no {@code HttpRequest}, no
 * {@code ByteBuf}, no {@code ChannelSupplier}, no socket type and no URL type anywhere in
 * {@code io.activej.jsonrpc.transport}. The in-memory transport of this feature is the proof that the
 * interface is implementable by something that has none of them; this test is the guard that keeps it so
 * when features 04, 06 and 07 add real transports beside it.
 *
 * <h2>Why code only, and not prose</h2>
 * The scan strips comments before matching. FR-084 forbids the SPI from <i>naming a type</i>, and the
 * authoritative obligation list in {@code contracts/service-api.md} deliberately says "{@code ByteBuf}
 * ownership, framing and connection lifetime are entirely the implementor's" — the SPI's documentation must
 * be able to tell an implementor which concerns are theirs, and it can only do that by naming them. A scan
 * that also policed prose would make the contract unwritable and would be enforcing a rule nobody stated.
 * <p>
 * Like {@code ModuleBoundaryTest}, this is a plain text scan: no bytecode or reflection scanning library
 * exists in this module's dependency set, and adding one to police a rule about dependencies would be
 * self-defeating. It cannot see a type referenced through a mechanism other than its name, which is exactly
 * the limitation {@code ModuleBoundaryTest} already documents.
 */
public class JsonRpcTransportBoundaryTest {
	/**
	 * Simple names no source of the transport package may name in code. Matched as whole words, so
	 * {@code JsonRpcTransport} is not mistaken for {@code Transport} and a {@code document} parameter is not
	 * mistaken for {@code Socket}.
	 */
	private static final List<String> FORBIDDEN_TYPE_NAMES = List.of(
		// HTTP / WebSocket
		"HttpRequest", "HttpResponse", "HttpMessage", "HttpHeaders", "HttpMethod", "HttpClient", "HttpServer",
		"IHttpClient", "AsyncServlet", "WebSocket", "IWebSocketClient",
		// buffers and streams
		"ByteBuf", "ByteBufs", "ByteBufPool", "ByteBuffer",
		"ChannelSupplier", "ChannelConsumer", "StreamSupplier", "StreamConsumer",
		// sockets and the network
		"Socket", "ServerSocket", "SocketChannel", "ServerSocketChannel", "DatagramChannel", "DatagramSocket",
		"AsyncTcpSocket", "ITcpSocket", "SocketAddress", "InetSocketAddress", "InetAddress", "SocketSettings",
		"SimpleServer", "AbstractServer", "AbstractReactiveServer",
		// addressing
		"URL", "URI", "UrlParser", "InputStream", "OutputStream", "Selector", "SelectionKey"
	);

	/** Package prefixes no source of the transport package may import. */
	private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
		"io.activej.http", "io.activej.net", "io.activej.csp", "io.activej.bytebuf", "io.activej.datastream",
		"io.activej.rpc", "java.net", "java.nio", "javax.net"
	);

	private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$]+)\\s*;");

	/** Block comments (Javadoc included) and line comments, in that order — prose is not code. */
	private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void transportPackageNamesNoTransportSpecificType() {
		List<String> violations = findViolations(transportRoot());
		if (!violations.isEmpty()) {
			fail("FR-084: the transport SPI must be implementable by a transport that has none of these types, " +
				 "so it may not name one:\n\t" + String.join("\n\t", violations));
		}
	}

	@Test
	public void theScannerActuallySeesTheSpi() {
		// guards the guard: an empty or mislocated scan would pass the test above forever
		List<Path> sources = sourcesUnder(transportRoot());
		assertTrue("io/activej/jsonrpc/transport was not found from " + Path.of("").toAbsolutePath(),
			Files.isDirectory(transportRoot()));
		assertTrue("the transport package must contain JsonRpcTransport.java, but the scan found " + sources,
			sources.stream().anyMatch(p -> p.getFileName().toString().equals("JsonRpcTransport.java")));
	}

	@Test
	public void aTransportTypeNamedInCodeIsRejected() throws IOException {
		Path root = tmp.newFolder("negative-scan").toPath();
		writeSource(root, "Offender", """
			import io.activej.bytebuf.ByteBuf;

			interface Offender {
				ByteBuf send(ByteBuf document);
			}
			""");

		List<String> violations = findViolations(root);

		assertTrue("a named ByteBuf must be reported: " + violations,
			violations.stream().anyMatch(v -> v.contains("ByteBuf")));
		assertTrue("the forbidden import must be reported too: " + violations,
			violations.stream().anyMatch(v -> v.contains("io.activej.bytebuf")));
	}

	@Test
	public void aTransportTypeNamedOnlyInProseIsAccepted() throws IOException {
		Path root = tmp.newFolder("prose-scan").toPath();
		writeSource(root, "Documented", """
			/**
			 * ByteBuf ownership, framing and connection lifetime are the implementor's: an HttpRequest body, a
			 * WebSocket frame or a ChannelSupplier of a Socket never appears here.
			 */
			interface Documented {
				// a URL is the implementor's business, not this interface's
				void send(byte[] document);
			}
			""");

		assertEquals("prose must not be policed — the SPI has to be able to say whose concern a type is",
			List.of(), findViolations(root));
	}

	private static List<String> findViolations(Path root) {
		List<String> violations = new ArrayList<>();
		for (Path source : sourcesUnder(root)) {
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

			String code = COMMENT.matcher(text).replaceAll(" ");
			for (String name : FORBIDDEN_TYPE_NAMES) {
				Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(code);
				if (matcher.find()) {
					violations.add(source + " names the transport-specific type " + name);
				}
			}
		}
		return violations;
	}

	private static void writeSource(Path root, String className, String body) throws IOException {
		Path dir = root.resolve("io/activej/jsonrpc/transport");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(className + ".java"),
			"package io.activej.jsonrpc.transport;\n\n" + body, StandardCharsets.UTF_8);
	}

	private static Path transportRoot() {
		Path local = Path.of("src", "main", "java", "io", "activej", "jsonrpc", "transport");
		if (Files.isDirectory(local)) return local;
		// running from the reactor root rather than the module basedir
		return Path.of("extra", "cloud-jsonrpc", "src", "main", "java", "io", "activej", "jsonrpc", "transport");
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
