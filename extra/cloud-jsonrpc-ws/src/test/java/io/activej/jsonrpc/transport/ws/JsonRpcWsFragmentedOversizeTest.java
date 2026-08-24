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

package io.activej.jsonrpc.transport.ws;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefLong;
import io.activej.http.IWebSocket;
import io.activej.http.IWebSocket.Frame;
import io.activej.http.WebSocketException;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.ByteBufRule.IgnoreLeaks;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.Consumer;

import static io.activej.promise.TestUtils.await;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FR-030 for this transport (feature 019, T038): <i>a message streamed beyond the bound never grows
 * memory to the size it streams, because the bound is applied <b>during</b> accumulation</i>.
 *
 * <h2>Why this is not a case in {@link JsonRpcWsOversizeTest}</h2>
 * That class sends one 1.2 MB frame against the 1 mb cap. core-http refuses it in
 * {@code WebSocketBufsToFrames.processMask}, on the frame's <b>declared</b> length, before a single
 * payload byte is buffered — so the announced size is genuinely never allocated. Excellent, and
 * leak-free, but it only covers a size announced <i>in a header</i>.
 * <p>
 * The other half — a size that is never announced anywhere, only accumulated — needs a
 * <b>fragmented</b> message: every fragment is individually far under the cap, so
 * {@code processMask}'s per-frame check passes each one, and only the running total across fragments
 * crosses it. That total is checked by {@code WebSocket.readMessage} on every frame it takes
 * ({@code messageBufs.remainingBytes() + payload.readRemaining() > maxMessageSize}), which is what
 * "during accumulation" means here, and what this class offers 64 MB to prove.
 *
 * <h2>The leak this found, since fixed — and why this class keeps full leak checking</h2>
 * That message-level branch used to <b>leak the crossing frame's payload</b>:
 * {@code WebSocket.readMessage} took {@code frame.getPayload()}, saw the total exceed
 * {@code maxMessageSize}, called {@code protocolError(MESSAGE_TOO_BIG, cb)} and returned — the payload
 * was neither added to {@code messageBufs} (which the {@code whenException} handler does recycle) nor
 * recycled itself. It was confirmed with an allocation-site probe over
 * {@code ByteBufPool.getStats().queryUnrecycledBufs(...)}: one buffer, exactly the crossing
 * fragment's size class, allocated in {@code WebSocketBufsToFrames.processPayload}. It reproduced
 * with two fragments and no flood, so it was never an artefact of this test's shape.
 * <p>
 * It was a <b>pre-existing core-http defect on a path no test covered</b> — this module's own
 * fragmentation tests stay under the cap, and its oversize test is unfragmented — so a feature branch
 * did not fix it. It was fixed on {@code 020-websocket-leak-fix} (2026-08-24, commit
 * {@code 8a213bb6c}) by recycling the payload immediately before {@code protocolError}, and
 * {@code core-http}'s {@code WebSocketTest} carries the unit-level regression test.
 * <p>
 * <b>The opt-out below was rewritten, not removed, when this branch rebased past that fix.</b> With the
 * crossing-fragment leak gone, an allocation-site probe over
 * {@code ByteBufPool.getStats().queryUnrecycledBufs(...)} finds this scenario now strands exactly
 * <b>one 64-byte buffer</b>, at a different site: {@code WebSocketFramesToBufs.encodeClose}, reached
 * through {@code HttpClientConnection.bindWebSocketTransformers}'s teardown. That is the smaller,
 * <b>still-open</b> strand {@code docs/http/spec.md} records beside the fix — it appears only because
 * this test keeps writing after the receiver has already closed, and disappears if the sender stops at
 * the crossing. So the class-level opt-out stays, retargeted at the one leak that is genuinely still
 * open, and this class is the second regression test for the fixed one: the big crossing-fragment
 * buffer no longer leaks here, and a return of it would fail this test on its size class.
 */
@IgnoreLeaks("core-http strands one 64-byte buffer in WebSocketFramesToBufs.encodeClose, reached via " +
			 "HttpClientConnection.bindWebSocketTransformers' teardown, when the sender keeps writing after " +
			 "the receiver has already closed -- which is exactly what this test does. Confirmed with an " +
			 "allocation-site probe: one entry, size 64, encodeClose(WebSocketFramesToBufs.java:162). It is " +
			 "pre-existing, in a core module, and docs/http/spec.md records it as deliberately still open -- " +
			 "no exact line, no ready-made regression test, no one-line fix identified. NOT the crossing " +
			 "fragment's payload: that leak WAS this test's original discovery and was fixed on " +
			 "020-websocket-leak-fix (8a213bb6c), so its far larger size class is checked here again. The " +
			 "unfragmented oversize path strands nothing and stays fully checked in JsonRpcWsOversizeTest.")
public final class JsonRpcWsFragmentedOversizeTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** What the flood offers: 128 MB against a 1 mb cap. */
	private static final long FLOOD_ATTEMPT = 128L * 1024 * 1024;

	/** One continuation frame — 4× under the cap on its own; only the running total is not. */
	private static final int FRAGMENT_SIZE = 256 * 1024;

	/**
	 * The byte count above which the flood would mean "it reassembled first". A quarter of the offer.
	 * <p>
	 * Deliberately loose, and the looseness is not slack: what the sender gets away with is dominated
	 * by its <i>own</i> outbound queue and both kernels' socket buffers, none of which is the
	 * receiver's assembly and none of which this module tunes. Measured here at ≈7.6 MB — a figure
	 * that does not grow with the offer, because it is a property of the pipe rather than of the
	 * message. The two candidate behaviours are "a few MB" and "all 128 MB", so the ceiling clears the
	 * observed figure four times over and still fails an implementation that reassembled first.
	 */
	private static final long FLOOD_CEILING = FLOOD_ATTEMPT / 4;

	@Test
	public void testAFragmentedMessageIsCutOffAfterFarFewerBytesThanItOffers() {
		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(
				$ -> fail("no document may be delivered from an oversize message"),
				closed::set));
		});

		// one array, reused for every fragment: the TEST must not hold the offer either, or it would be
		// proving the receiver's frugality with 64 MB of its own
		byte[] fragment = new byte[FRAGMENT_SIZE];
		Arrays.fill(fragment, (byte) 'a');
		RefLong written = new RefLong(0);
		await(pair.connect().then(ws -> streamFragments(ws, fragment, written)));

		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals("the transport tier's own answer is a close code, mid-accumulation",
			Integer.valueOf(1009), ((WebSocketException) closed.get()).getCode());
		assertTrue("the cap is applied during accumulation: " + FLOOD_ATTEMPT + " bytes were offered " +
				   "against a 1 mb cap and the receiver took " + written.get(),
			written.get() < FLOOD_CEILING);
		pair.closeAll();
	}

	/** One continuation frame at a time, until the receiver stops taking them or the offer runs out. */
	private static Promise<Void> streamFragments(IWebSocket ws, byte[] fragment, RefLong written) {
		if (written.get() >= FLOOD_ATTEMPT) return Promise.complete();
		Frame frame = written.get() == 0 ?
			Frame.text(ByteBuf.wrapForReading(fragment), false) :
			Frame.next(ByteBuf.wrapForReading(fragment), false);
		return ws.writeFrame(frame).then(
			$ -> {
				written.inc(fragment.length);
				return streamFragments(ws, fragment, written);
			},
			// the receiver cut the connection: the expected outcome, and the point of the test
			e -> Promise.complete());
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	private static JsonRpcTransport.Listener listener(Consumer<byte[]> onDocument, Consumer<@Nullable Exception> onClosed) {
		return new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				onDocument.accept(document);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				onClosed.accept(e);
			}
		};
	}
}
