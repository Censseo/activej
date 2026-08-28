package io.activej.rpc;

import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.rpc.client.RpcClient;
import io.activej.rpc.server.RpcServer;
import io.activej.serializer.annotations.SerializeRecord;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.ClassBuilderConstantsRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.promise.TestUtils.await;
import static io.activej.rpc.client.sender.strategy.RpcStrategies.server;
import static io.activej.test.TestUtils.getFreePort;
import static org.junit.Assert.assertEquals;

public final class RpcSslShutdownTest {
	private static final String KEYSTORE_PATH = "./src/test/resources/keystore.jks";
	private static final String KEYSTORE_PASS = "testtest";
	private static final String KEY_PASS = "testtest";

	private static final String TRUSTSTORE_PATH = "./src/test/resources/truststore.jks";
	private static final String TRUSTSTORE_PASS = "testtest";

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@Rule
	public final ClassBuilderConstantsRule classBuilderConstantsRule = new ClassBuilderConstantsRule();

	private InetSocketAddress address;
	private SSLContext sslContext;
	private Executor sslExecutor;

	@Before
	public void setUp() throws Exception {
		// "localhost" on both sides: RpcClient wraps with the hostname-verifying overload
		// (RpcClient.java:477), so getHostString() must match the certificate's SAN.
		address = new InetSocketAddress("localhost", getFreePort());
		sslContext = createSslContext();
		sslExecutor = Executors.newSingleThreadExecutor();
	}

	@Test
	public void testGracefulClientShutdownOverSsl() throws Exception {
		NioReactor reactor = Reactor.getCurrentReactor();
		List<Class<?>> messageTypes = List.of(Request.class, Response.class);

		RpcServer rpcServer = RpcServer.builder(reactor)
			.withMessageTypes(messageTypes)
			.withHandler(Request.class, request -> Promise.of(new Response("pong")))
			.withSslListenAddress(sslContext, sslExecutor, address)
			.build();

		RpcClient rpcClient = RpcClient.builder(reactor)
			.withMessageTypes(messageTypes)
			.withSslEnabled(sslContext, sslExecutor)
			.withStrategy(server(address))
			.build();

		rpcServer.listen();

		// The non-forced stop is the deterministic path into the defect:
		// RpcClient.stop() -> RpcClientConnection.shutdown() -> RpcStream.sendEndOfStream()
		// -> ChannelConsumers.ofSocket(...) acknowledgement -> socket.write(null)
		// on a fully open, fully handshaken TLS socket.
		Response response = await(rpcClient.start()
			.then(() -> rpcClient.<Request, Response>sendRequest(new Request("ping")))
			.then(result -> rpcClient.stop().map($ -> result))
			.whenComplete(rpcServer::close));

		assertEquals("pong", response.message());
	}

	@SerializeRecord
	public record Request(String message) {}

	@SerializeRecord
	public record Response(String message) {}

	private static SSLContext createSslContext() throws Exception {
		SSLContext instance = SSLContext.getInstance("TLSv1.2");

		KeyStore keyStore = KeyStore.getInstance("JKS");
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		try (InputStream input = new FileInputStream(KEYSTORE_PATH)) {
			keyStore.load(input, KEYSTORE_PASS.toCharArray());
		}
		kmf.init(keyStore, KEY_PASS.toCharArray());

		KeyStore trustStore = KeyStore.getInstance("JKS");
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		try (InputStream input = new FileInputStream(TRUSTSTORE_PATH)) {
			trustStore.load(input, TRUSTSTORE_PASS.toCharArray());
		}
		tmf.init(trustStore);

		instance.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
		return instance;
	}
}
