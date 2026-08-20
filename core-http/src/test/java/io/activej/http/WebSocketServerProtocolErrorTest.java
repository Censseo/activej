package io.activej.http;

import io.activej.common.ref.Ref;
import io.activej.eventloop.Eventloop;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Base64;

import static io.activej.bytebuf.ByteBufStrings.decodeAscii;
import static io.activej.http.TestUtils.readFully;
import static io.activej.test.TestUtils.getFreePort;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

/**
 * An end-to-end check of the WebSocket protocol-error path of {@link WebSocketBufsToFrames}: a real
 * {@link HttpServer}, a raw client socket and a deliberately malformed client frame on the wire.
 * <p>
 * A client frame MUST be masked (RFC 6455 - 5.1), so an unmasked one is a protocol error and the server
 * must answer with a {@code 1002} close frame and tear the connection down, rather than keep decoding
 * against already recycled state.
 */
public final class WebSocketServerProtocolErrorTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int TIMEOUT_MILLIS = 10_000;

	private Eventloop eventloop;
	private int port;

	@Before
	public void setUp() {
		eventloop = (Eventloop) Reactor.getCurrentReactor();
		port = getFreePort();
	}

	@Test
	public void unmaskedClientFrameIsAnsweredWithProtocolErrorCloseFrame() throws Exception {
		Ref<Exception> serverException = new Ref<>();

		HttpServer server = HttpServer.builder(eventloop, RoutingServlet.builder(eventloop)
				.withWebSocket("/", webSocket -> webSocket.readMessage()
					.whenResult(message -> {
						if (message != null) message.recycle();
					})
					.whenException(serverException::set))
				.build())
			.withListenPort(port)
			.withAcceptOnce()
			.build();
		server.listen();

		Thread thread = new Thread(eventloop);
		thread.start();

		try (Socket socket = new Socket()) {
			socket.setSoTimeout(TIMEOUT_MILLIS);
			socket.setTcpNoDelay(true);
			socket.connect(new InetSocketAddress("localhost", port));

			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write(handshakeRequest().getBytes(UTF_8));
			out.flush();

			String responseHead = readHead(in);
			assertTrue(responseHead, responseHead.startsWith("HTTP/1.1 101"));

			// Unmasked "Hello" TEXT frame — a client MUST mask, RFC 6455 - 5.1
			out.write(new byte[]{(byte) 0x81, (byte) 0x05, (byte) 0x48, (byte) 0x65, (byte) 0x6c, (byte) 0x6c, (byte) 0x6f});
			out.flush();

			// server frames are never masked
			byte[] closeHeader = new byte[2];
			readFully(in, closeHeader);
			assertEquals(0x88, closeHeader[0] & 0xFF); // FIN + OP_CLOSE
			int payloadLength = closeHeader[1] & 0xFF;
			assertTrue("Close payload must carry a status code", payloadLength >= 2);

			byte[] closePayload = new byte[payloadLength];
			readFully(in, closePayload);

			int closeCode = ((closePayload[0] & 0xFF) << 8) | (closePayload[1] & 0xFF);
			assertEquals(1002, closeCode);
			assertEquals("Message should be masked", new String(closePayload, 2, payloadLength - 2, UTF_8));

			assertEquals(-1, in.read());
		}

		// the eventloop has nothing left to do once the single accepted connection is torn down
		thread.join(TIMEOUT_MILLIS);
		assertFalse("Eventloop did not finish, the connection was not torn down", thread.isAlive());

		assertTrue(String.valueOf(serverException.get()), serverException.get() instanceof WebSocketException);
		WebSocketException webSocketException = (WebSocketException) serverException.get();
		assertEquals(Integer.valueOf(1002), webSocketException.getCode());
		assertEquals("Message should be masked", webSocketException.getReason());
	}

	private String handshakeRequest() {
		String key = Base64.getEncoder().encodeToString(new byte[16]);
		return
			"GET / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Upgrade: websocket\r\n" +
			"Connection: Upgrade\r\n" +
			"Sec-WebSocket-Key: " + key + "\r\n" +
			"Sec-WebSocket-Version: 13\r\n" +
			"\r\n";
	}

	private static String readHead(InputStream in) throws IOException {
		StringBuilder head = new StringBuilder();
		byte[] single = new byte[1];
		while (!head.toString().endsWith("\r\n\r\n")) {
			readFully(in, single);
			head.append(decodeAscii(single));
		}
		return head.toString();
	}
}
