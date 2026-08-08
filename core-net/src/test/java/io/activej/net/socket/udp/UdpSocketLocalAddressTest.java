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

package io.activej.net.socket.udp;

import io.activej.async.exception.AsyncCloseException;
import io.activej.async.callback.AsyncComputation;
import io.activej.async.callback.Callback;
import io.activej.common.function.RunnableEx;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.net.DatagramSocketSettings;
import io.activej.reactor.net.ServerSocketSettings;
import io.activej.reactor.nio.NioReactor;
import io.activej.reactor.schedule.ScheduledRunnable;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T004 / FR-002…FR-005 — {@link IUdpSocket#getLocalAddress()}: the address a UDP socket is bound to,
 * with the {@link java.nio.channels.ClosedChannelException} → {@code null} split (FR-004) that keeps
 * a genuine {@link IOException} from hiding behind the same value that means "closed" (FR-005).
 * <p>
 * The {@code ClosedChannelException} split is the load-bearing clause of the contract: returning
 * {@code null} on <b>every</b> {@code IOException} would hide a genuine fault behind the same value
 * that means "closed"; rethrowing on the closed path would make the accessor break the teardown that
 * called it. {@link #aNonClosedIoFailureIsSurfacedNotHidden} is the case that keeps the two
 * requirements distinct — without it, widening the catch would still pass the suite.
 */
public final class UdpSocketLocalAddressTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void boundSocketReportsItsAddress() throws IOException {
		DatagramChannel channel = NioReactor.createDatagramChannel(
			DatagramSocketSettings.create(), new InetSocketAddress(0), null);

		// The connect completes synchronously: UdpSocket.connect wraps an already-created socket and
		// registers it with the selector. (Driving it through TestUtils.await would not return — the
		// registered key keeps the eventloop alive while the socket is open.)
		Promise<UdpSocket> connected = UdpSocket.connect(Reactor.getCurrentReactor(), channel);
		assertTrue("the connect must have completed: " + connected, connected.isResult());
		UdpSocket socket = connected.getResult();
		try {
			InetSocketAddress local = socket.getLocalAddress();
			assertNotNull("a bound socket reports its address", local);
			assertNotEquals("the :0 port was resolved by the OS", 0, local.getPort());
			assertEquals("the reported address matches the channel's", channel.getLocalAddress(), local);
		} finally {
			socket.close();
		}
	}

	@Test
	public void closedSocketReportsNoAddress() throws IOException {
		DatagramChannel channel = NioReactor.createDatagramChannel(
			DatagramSocketSettings.create(), new InetSocketAddress(0), null);
		UdpSocket socket = UdpSocket.connect(Reactor.getCurrentReactor(), channel).getResult();
		socket.close();

		assertNull("a closed socket reports no address — not a thrown ClosedChannelException (FR-004)",
			socket.getLocalAddress());
	}

	@Test
	public void aNonClosedIoFailureIsSurfacedNotHidden() throws IOException {
		IOException failure = new IOException("the channel's local address failed for a reason other than closure");
		FailingLocalAddressChannel channel = new FailingLocalAddressChannel(failure);
		// UdpSocket registers the channel with the reactor's selector, which requires non-blocking mode.
		channel.configureBlocking(false);

		// The JDK will not register a hand-written DatagramChannel with a real selector (SelectorImpl
		// demands an internal sun.nio.ch.SelChImpl, JDK 17+), so the socket is built over a stub
		// NioReactor/selector pair. The class under test is still the real UdpSocket, holding the
		// failing channel.
		FakeNioReactor reactor = new FakeNioReactor();
		UdpSocket socket = UdpSocket.connect(reactor, channel).getResult();
		try {
			UncheckedIOException thrown = assertThrows(UncheckedIOException.class, socket::getLocalAddress);
			assertSame("FR-005: the cause is carried, not replaced", failure, thrown.getCause());
		} finally {
			socket.close();
		}
	}

	@Test
	public void unimplementedSocketReportsNoAddress() {
		// The compile-time proof that the method is defaulted (FR-002): an implementer of only the
		// three original methods still compiles, and answers null.
		IUdpSocket socket = new IUdpSocket() {
			@Override
			public Promise<UdpPacket> receive() {
				return Promise.ofException(new AsyncCloseException());
			}

			@Override
			public Promise<Void> send(UdpPacket packet) {
				return Promise.ofException(new AsyncCloseException());
			}

			@Override
			public void close() {}
		};
		assertNull("the default answers null — 'I do not model a local address'", socket.getLocalAddress());
	}

	/** A {@link DatagramChannel} whose {@link #getLocalAddress()} fails with a plain {@link IOException}. */
	private static final class FailingLocalAddressChannel extends DatagramChannel {
		private final IOException failure;

		private FailingLocalAddressChannel(IOException failure) {
			super(SelectorProvider.provider());
			this.failure = failure;
		}

		@Override
		public DatagramChannel bind(SocketAddress local) {
			return this;
		}

		@Override
		public <T> DatagramChannel setOption(SocketOption<T> name, T value) {
			return this;
		}

		@Override
		public DatagramSocket socket() {
			return null;
		}

		@Override
		public boolean isConnected() {
			return false;
		}

		@Override
		public DatagramChannel connect(SocketAddress remote) {
			return this;
		}

		@Override
		public DatagramChannel disconnect() {
			return this;
		}

		@Override
		public SocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public SocketAddress receive(ByteBuffer dst) {
			return null;
		}

		@Override
		public int send(ByteBuffer src, SocketAddress target) {
			return 0;
		}

		@Override
		public int read(ByteBuffer dst) {
			return 0;
		}

		@Override
		public long read(ByteBuffer[] dsts, int offset, int length) {
			return 0;
		}

		@Override
		public int write(ByteBuffer src) {
			return 0;
		}

		@Override
		public long write(ByteBuffer[] srcs, int offset, int length) {
			return 0;
		}

		@Override
		public SocketAddress getLocalAddress() throws IOException {
			throw failure;
		}

		@Override
		public Set<SocketOption<?>> supportedOptions() {
			return Set.of();
		}

		@Override
		public <T> T getOption(SocketOption<T> name) {
			return null;
		}

		@Override
		public MembershipKey join(InetAddress group, NetworkInterface interf) throws IOException {
			return null;
		}

		@Override
		public MembershipKey join(InetAddress group, NetworkInterface interf, InetAddress source) throws IOException {
			return null;
		}

		@Override
		protected void implCloseSelectableChannel() {}

		@Override
		protected void implConfigureBlocking(boolean block) throws IOException {}
	}

	/**
	 * A {@link NioReactor} that answers registration with a stub selector — the minimal surface
	 * {@link UdpSocket}'s constructor and {@code close()} touch. Everything else throws, so a test
	 * that accidentally drives real I/O through it fails loudly.
	 */
	private static final class FakeNioReactor implements NioReactor {
		private final FakeSelector selector = new FakeSelector();

		@Override
		public Selector ensureSelector() {
			return selector;
		}

		@Override
		public Selector getSelector() {
			return selector;
		}

		@Override
		public void closeChannel(@Nullable SelectableChannel channel, @Nullable SelectionKey key) {
			if (key != null) key.cancel();
			try {
				if (channel != null) channel.close();
			} catch (IOException e) {
				throw new AssertionError("closing the test channel failed", e);
			}
		}

		@Override
		public boolean inReactorThread() {
			return true;
		}

		@Override
		public void post(Runnable runnable) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void postLast(Runnable runnable) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void postNext(Runnable runnable) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void startExternalTask() {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void completeExternalTask() {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void logFatalError(Throwable e, @Nullable Object context) {
			throw new AssertionError("no fatal error may reach the stub reactor", e);
		}

		@Override
		public void schedule(ScheduledRunnable scheduledRunnable) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void scheduleBackground(ScheduledRunnable scheduledRunnable) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public long currentTimeMillis() {
			return 0;
		}

		@Override
		public void execute(Runnable command) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public CompletableFuture<Void> submit(RunnableEx runnable) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public <T> CompletableFuture<T> submit(AsyncComputation<? extends T> computation) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public ServerSocketChannel listen(
			InetSocketAddress address, ServerSocketSettings settings, Consumer<SocketChannel> acceptCallback
		) throws IOException {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void connect(SocketAddress address, Callback<SocketChannel> cb) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void connect(SocketAddress address, Duration timeout, Callback<SocketChannel> cb) {
			throw new AssertionError("no real work may run on the stub reactor");
		}

		@Override
		public void connect(SocketAddress address, long timeoutMillis, Callback<SocketChannel> cb) {
			throw new AssertionError("no real work may run on the stub reactor");
		}
	}

	/** A selector that hands out a stub key for whatever registers, and never selects. */
	private static final class FakeSelector extends AbstractSelector {
		private FakeSelector() {
			super(SelectorProvider.provider());
		}

		@Override
		protected SelectionKey register(AbstractSelectableChannel channel, int ops, Object attachment) {
			FakeSelectionKey key = new FakeSelectionKey(channel, this);
			key.attach(attachment);
			return key;
		}

		@Override
		protected void implCloseSelector() {}

		@Override
		public Set<SelectionKey> keys() {
			return Set.of();
		}

		@Override
		public Set<SelectionKey> selectedKeys() {
			return Set.of();
		}

		@Override
		public int selectNow() {
			return 0;
		}

		@Override
		public int select(long timeout) {
			return 0;
		}

		@Override
		public int select() {
			return 0;
		}

		@Override
		public Selector wakeup() {
			return this;
		}
	}

	private static final class FakeSelectionKey extends SelectionKey {
		private final SelectableChannel channel;
		private final Selector selector;

		private FakeSelectionKey(SelectableChannel channel, Selector selector) {
			this.channel = channel;
			this.selector = selector;
		}

		@Override
		public SelectableChannel channel() {
			return channel;
		}

		@Override
		public Selector selector() {
			return selector;
		}

		@Override
		public boolean isValid() {
			return true;
		}

		@Override
		public void cancel() {}

		@Override
		public int interestOps() {
			return 0;
		}

		@Override
		public SelectionKey interestOps(int ops) {
			return this;
		}

		@Override
		public int readyOps() {
			return 0;
		}
	}
}
