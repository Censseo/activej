package io.activej.rpc.client;

import io.activej.async.callback.Callback;
import io.activej.eventloop.Eventloop;
import io.activej.reactor.Reactor;
import io.activej.rpc.protocol.RpcMandatoryData;
import io.activej.rpc.protocol.RpcMessage;
import io.activej.rpc.protocol.RpcOverloadException;
import io.activej.serializer.annotations.SerializeRecord;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class RpcClientOverloadedQueueLimitTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final EventloopRule eventloopRule = new EventloopRule();

	@Test
	public void testOverloadedQueueLimitRejectsExcessMandatoryRequests() {
		Eventloop reactor = Reactor.getCurrentReactor();
		RpcClient rpcClient = RpcClient.builder(reactor)
			.withMessageTypes(NormalRequest.class, MandatoryRequest.class, Response.class)
			.withOverloadedQueueLimit(2)
			.build();

		List<RpcMessage> sent = new ArrayList<>();
		RpcClientConnection connection = new RpcClientConnection(rpcClient, new InetSocketAddress(0), null, 0L);

		connection.onSenderReady(sent::add);
		connection.onSenderSuspended();

		ResultCallback<Response> cb1 = new ResultCallback<>();
		connection.sendRequest(new MandatoryRequest(1), cb1);
		assertFalse(cb1.called);
		assertEquals(1, connection.getOverloadedQueueSize());
		assertEquals(1, sent.size());

		ResultCallback<Response> cb2 = new ResultCallback<>();
		connection.sendRequest(new MandatoryRequest(2), cb2);
		assertFalse(cb2.called);
		assertEquals(2, connection.getOverloadedQueueSize());
		assertEquals(2, sent.size());

		ResultCallback<Response> cb3 = new ResultCallback<>();
		connection.sendRequest(new MandatoryRequest(3), cb3);
		assertTrue(cb3.called);
		assertNull(cb3.result);
		assertTrue(cb3.exception instanceof RpcOverloadException);
		assertEquals(2, connection.getOverloadedQueueSize());
		assertEquals(2, sent.size());

		connection.accept(new RpcMessage(1, new Response(1)));
		assertTrue(cb1.called);
		assertNull(cb1.exception);
		assertNotNull(cb1.result);
		assertEquals(1, cb1.result.id());
		assertEquals(1, connection.getOverloadedQueueSize());

		ResultCallback<Response> cb4 = new ResultCallback<>();
		connection.sendRequest(new MandatoryRequest(4), cb4);
		assertFalse(cb4.called);
		assertEquals(2, connection.getOverloadedQueueSize());
		assertEquals(3, sent.size());

		connection.accept(new RpcMessage(2, new Response(2)));
		connection.accept(new RpcMessage(3, new Response(4)));

		assertTrue(cb2.called);
		assertNull(cb2.exception);
		assertNotNull(cb2.result);
		assertEquals(2, cb2.result.id());

		assertTrue(cb4.called);
		assertNull(cb4.exception);
		assertNotNull(cb4.result);
		assertEquals(4, cb4.result.id());

		assertEquals(0, connection.getOverloadedQueueSize());
	}

	@Test
	public void testOverloadedRejectsNonMandatoryRequests() {
		Eventloop reactor = Reactor.getCurrentReactor();
		RpcClient rpcClient = RpcClient.builder(reactor)
			.withMessageTypes(NormalRequest.class, MandatoryRequest.class, Response.class)
			.withOverloadedQueueLimit(1)
			.build();

		List<RpcMessage> sent = new ArrayList<>();
		RpcClientConnection connection = new RpcClientConnection(rpcClient, new InetSocketAddress(0), null, 0L);

		connection.onSenderReady(sent::add);
		connection.onSenderSuspended();

		ResultCallback<Response> cb = new ResultCallback<>();
		connection.sendRequest(new NormalRequest(1), cb);
		assertTrue(cb.called);
		assertNull(cb.result);
		assertTrue(cb.exception instanceof RpcOverloadException);
		assertEquals(0, sent.size());
		assertEquals(0, connection.getOverloadedQueueSize());
	}

	@SerializeRecord
	public record NormalRequest(int id) {}

	@SerializeRecord
	public record MandatoryRequest(int id) implements RpcMandatoryData {}

	@SerializeRecord
	public record Response(int id) {}

	private static final class ResultCallback<T> implements Callback<T> {
		private boolean called;
		private @Nullable T result;
		private @Nullable Exception exception;

		@Override
		public void accept(T result, @Nullable Exception e) {
			called = true;
			this.result = result;
			this.exception = e;
		}
	}
}
