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

package io.activej.http3.frame;

import io.activej.http3.Http3Errors;
import io.activej.http3.Http3Exception;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Drives {@link Http3FrameSequence} directly, with no transport: the RFC 9114 §4.1 request-stream
 * grammar {@code HEADERS -> DATA* -> HEADERS?} and its violations (FR-025).
 */
public class Http3FrameSequenceTest {

	@Test
	public void headersThenDataStarThenTrailingHeadersIsAccepted() throws Http3Exception {
		Http3FrameSequence sequence = new Http3FrameSequence();
		assertEquals(Http3FrameSequence.State.HEADERS_DONE, sequence.accept(HeadersFrame.TYPE));
		assertEquals(Http3FrameSequence.State.BODY, sequence.accept(DataFrame.TYPE));
		assertEquals(Http3FrameSequence.State.BODY, sequence.accept(DataFrame.TYPE));
		assertEquals(Http3FrameSequence.State.TRAILERS_DONE, sequence.accept(HeadersFrame.TYPE));
	}

	@Test
	public void headersWithNoDataAndNoTrailersIsAccepted() throws Http3Exception {
		Http3FrameSequence sequence = new Http3FrameSequence();
		assertEquals(Http3FrameSequence.State.HEADERS_DONE, sequence.accept(HeadersFrame.TYPE));
	}

	@Test
	public void headersDirectlyFollowedByTrailingHeadersIsAccepted() throws Http3Exception {
		// HEADERS DATA* HEADERS? -- zero DATA frames before the trailer HEADERS is legal.
		Http3FrameSequence sequence = new Http3FrameSequence();
		sequence.accept(HeadersFrame.TYPE);
		assertEquals(Http3FrameSequence.State.TRAILERS_DONE, sequence.accept(HeadersFrame.TYPE));
	}

	@Test
	public void dataBeforeHeadersIsRejected() {
		Http3FrameSequence sequence = new Http3FrameSequence();
		Http3Exception e = assertThrows(Http3Exception.class, () -> sequence.accept(DataFrame.TYPE));
		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, e.errorCode());
	}

	@Test
	public void aThirdHeadersFrameIsRejected() throws Http3Exception {
		Http3FrameSequence sequence = new Http3FrameSequence();
		sequence.accept(HeadersFrame.TYPE);
		sequence.accept(DataFrame.TYPE);
		sequence.accept(HeadersFrame.TYPE); // trailers
		Http3Exception e = assertThrows(Http3Exception.class, () -> sequence.accept(HeadersFrame.TYPE));
		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, e.errorCode());
	}

	@Test
	public void dataAfterTrailingHeadersIsRejected() throws Http3Exception {
		Http3FrameSequence sequence = new Http3FrameSequence();
		sequence.accept(HeadersFrame.TYPE);
		sequence.accept(HeadersFrame.TYPE); // trailers
		Http3Exception e = assertThrows(Http3Exception.class, () -> sequence.accept(DataFrame.TYPE));
		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, e.errorCode());
	}

	/**
	 * The control-only frame types, each of which RFC 9114 §7.2.3/§7.2.4/§7.2.6/§7.2.7 requires on the
	 * control stream and nowhere else — a rule about <i>where</i> a frame may travel, hence
	 * {@code H3_FRAME_UNEXPECTED}.
	 * <p>
	 * PUSH_PROMISE ({@code 0x05}) is deliberately <b>not</b> in this loop: RFC 9114 §7.2.5 judges it
	 * against the push limit rather than against the stream it arrived on, so it carries
	 * {@code H3_ID_ERROR} and has its own test below.
	 */
	@Test
	public void controlFrameTypesAreNeverPermittedOnARequestStream() throws Http3Exception {
		for (long illegalType : new long[] {
			SettingsFrame.TYPE, GoAwayFrame.TYPE, MaxPushIdFrame.TYPE, CancelPushFrame.TYPE
		}) {
			Http3FrameSequence idle = new Http3FrameSequence();
			Http3Exception e = assertThrows(Http3Exception.class, () -> idle.accept(illegalType));
			assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, e.errorCode());

			Http3FrameSequence afterHeaders = new Http3FrameSequence();
			afterHeaders.accept(HeadersFrame.TYPE);
			Http3Exception e2 = assertThrows(Http3Exception.class, () -> afterHeaders.accept(illegalType));
			assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, e2.errorCode());
		}
	}

	/**
	 * FR-040 / US8 §1: a PUSH_PROMISE is refused with {@code H3_ID_ERROR}, not with the
	 * {@code H3_FRAME_UNEXPECTED} the control-only types above get.
	 * <p>
	 * RFC 9114 §7.2.5 makes a promise legal only against a push id the receiving client granted with a
	 * MAX_PUSH_ID it sent, and this implementation never sends one — so its push limit is 0 on every
	 * connection and every promise names an identifier that was never issued. That is an identifier
	 * error, and a peer told "unexpected frame" instead would be told the wrong thing about why.
	 */
	@Test
	public void pushPromiseIsAnIdErrorRatherThanAnUnexpectedFrame() throws Http3Exception {
		long pushPromise = 0x05L;

		Http3FrameSequence idle = new Http3FrameSequence();
		Http3Exception e = assertThrows(Http3Exception.class, () -> idle.accept(pushPromise));
		assertEquals(Http3Errors.H3_ID_ERROR, e.errorCode());

		Http3FrameSequence afterHeaders = new Http3FrameSequence();
		afterHeaders.accept(HeadersFrame.TYPE);
		Http3Exception e2 = assertThrows(Http3Exception.class, () -> afterHeaders.accept(pushPromise));
		assertEquals(Http3Errors.H3_ID_ERROR, e2.errorCode());

		Http3FrameSequence afterData = new Http3FrameSequence();
		afterData.accept(HeadersFrame.TYPE);
		afterData.accept(DataFrame.TYPE);
		Http3Exception e3 = assertThrows(Http3Exception.class, () -> afterData.accept(pushPromise));
		assertEquals(Http3Errors.H3_ID_ERROR, e3.errorCode());
	}

	@Test
	public void reservedHttp2FrameTypesAreRejected() {
		for (long reservedType : new long[] {0x02L, 0x06L, 0x08L, 0x09L}) {
			Http3FrameSequence sequence = new Http3FrameSequence();
			Http3Exception e = assertThrows(Http3Exception.class, () -> sequence.accept(reservedType));
			assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, e.errorCode());
		}
	}

	@Test
	public void unknownAndGreaseFrameTypesAreToleratedNotRejected() throws Http3Exception {
		// FR-023 / RFC 9114 §9: a genuinely unknown or GREASE frame type (0x1f * N + 0x21) must be
		// discarded without failing the connection -- it is not a sequence violation, unlike the
		// control-only types above. Regression test for a bug where accept() rejected everything
		// that wasn't DATA/HEADERS, which would tear down every connection from a GREASE-sending
		// client (e.g. Chrome sends one on every connection).
		Http3FrameSequence sequence = new Http3FrameSequence();
		assertEquals(Http3FrameSequence.State.HEADERS_DONE, sequence.accept(HeadersFrame.TYPE));
		for (long grease : new long[] {0x21L, 0x40L, 0x5fL}) {
			assertEquals(Http3FrameSequence.State.HEADERS_DONE, sequence.accept(grease));
		}
		assertEquals(Http3FrameSequence.State.BODY, sequence.accept(DataFrame.TYPE));
		for (long grease : new long[] {0x21L, 0x40L, 0x5fL}) {
			assertEquals(Http3FrameSequence.State.BODY, sequence.accept(grease));
		}
		assertEquals(Http3FrameSequence.State.TRAILERS_DONE, sequence.accept(HeadersFrame.TYPE));
	}
}
