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

package io.activej.http3.qpack;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.Checks;
import io.activej.http3.Http3Errors;
import io.activej.http3.qpack.QpackInstructions.DecoderInstruction;
import org.jetbrains.annotations.Nullable;

import static io.activej.common.Checks.checkArgument;

/**
 * The peer's QPACK <b>decoder</b> stream (type {@code 0x03}), applied to a
 * {@link QpackDynamicEncoder}: RFC 9204 §4.4's three instructions parsed incrementally, with the
 * FR-028 size bound applied so a peer that never finishes an instruction closes the connection
 * instead of growing this buffer.
 * <p>
 * Synchronous and non-reactive (ADR-016), and the exact counterpart of
 * {@link QpackEncoderStreamReader} — the two inbound QPACK paths in {@code Http3Connection} read
 * alike because of it. {@link #recycle()} is mandatory on every abandon path.
 *
 * <h4>Why the remainder bound alone is enough here</h4>
 * All three decoder instructions are a bare RFC 9204 §4.1.1 prefixed integer with no string
 * attached, and {@link QpackIntegers} already refuses an over-long continuation run — so an
 * instruction that arrives whole cannot be large, and the only unbounded form left is an
 * <i>unterminated</i> continuation run, which reads as "not yet whole" rather than as malformed and
 * is exactly what a bound on the retained remainder catches. The encoder stream needs both halves of
 * that check because its instructions carry name and value strings; this one does not.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.4">RFC 9204 §4.4 — Decoder
 * Instructions</a>
 */
public final class QpackDecoderStreamReader {
	private static final boolean CHECKS = Checks.isEnabled(QpackDecoderStreamReader.class);

	private final QpackDynamicEncoder encoder;
	private final long maxInstructionSize;

	private @Nullable ByteBuf pending;
	private long instructionsApplied;

	/**
	 * @param maxInstructionSize the {@code Http3Settings.qpackMaxInstructionSize()} bound, in bytes,
	 *                           above which an instruction is {@code QPACK_DECODER_STREAM_ERROR}
	 *                           rather than buffered (FR-028, SI-3)
	 */
	public QpackDecoderStreamReader(QpackDynamicEncoder encoder, long maxInstructionSize) {
		if (CHECKS) checkArgument(maxInstructionSize > 0, "maxInstructionSize must be positive");
		this.encoder = encoder;
		this.maxInstructionSize = maxInstructionSize;
	}

	/**
	 * Applies every whole instruction {@code buf} completes, retaining whatever partial instruction
	 * trails it.
	 * <p>
	 * <b>Takes ownership of {@code buf} on every path, a throw included</b>: on failure the retained
	 * remainder is released too, so a caller that also calls {@link #recycle()} while unwinding is
	 * safe.
	 *
	 * @return how many instructions this call applied
	 * @throws QpackException {@link Http3Errors#QPACK_DECODER_STREAM_ERROR} at connection scope, for
	 *                        every cause of FR-030 and for the {@code maxInstructionSize} bound
	 */
	public int feed(ByteBuf buf) throws QpackException {
		pending = pending == null ? buf : ByteBufPool.append(pending, buf);
		int applied = 0;
		try {
			DecoderInstruction instruction;
			while ((instruction = QpackInstructions.readDecoderInstruction(pending)) != null) {
				encoder.applyDecoderInstruction(instruction);
				applied++;
				instructionsApplied++;
			}
			requireWithinBound(pending.readRemaining());
		} catch (QpackException e) {
			recycle();
			throw e;
		}
		if (!pending.canRead()) {
			pending.recycle();
			pending = null;
		}
		return applied;
	}

	/**
	 * Releases the retained partial instruction. Mandatory on connection close and on every abort
	 * path; idempotent, and a no-op when nothing is retained.
	 */
	public void recycle() {
		if (pending != null) {
			pending.recycle();
			pending = null;
		}
	}

	/** Instructions applied over the life of this reader. */
	public long instructionsApplied() {
		return instructionsApplied;
	}

	/** Bytes of a partial instruction currently retained; never above {@code maxInstructionSize}. */
	public int pendingBytes() {
		return pending == null ? 0 : pending.readRemaining();
	}

	private void requireWithinBound(long bytes) throws QpackException {
		if (bytes > maxInstructionSize) {
			throw QpackException.connectionError(Http3Errors.QPACK_DECODER_STREAM_ERROR,
				"a QPACK decoder-stream instruction above the configured maximum instruction size");
		}
	}
}
