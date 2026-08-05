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
import io.activej.http.HttpHeader;
import io.activej.http3.Http3Errors;
import io.activej.http3.qpack.QpackInstructions.Duplicate;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertWithLiteralName;
import io.activej.http3.qpack.QpackInstructions.InsertWithNameReference;
import io.activej.http3.qpack.QpackInstructions.SetDynamicTableCapacity;
import org.jetbrains.annotations.Nullable;

import static io.activej.common.Checks.checkArgument;

/**
 * The peer's QPACK <b>encoder</b> stream (type {@code 0x02}), applied to a
 * {@link QpackDynamicDecoder}'s table: RFC 9204 §4.3's four instructions parsed incrementally, each
 * semantic rule of FR-029 enforced, and the FR-028 size bound applied so a peer that never finishes
 * an instruction closes the connection instead of growing this buffer.
 * <p>
 * Synchronous and non-reactive (ADR-016). It owns the partial instruction it is part-way through and
 * nothing else — the same shape, and the same obligation, as {@code Http3FrameReader}:
 * {@link #recycle()} is mandatory on every abandon path.
 *
 * <h4>The bound applies to every instruction, buffered or not</h4>
 * {@code qpackMaxInstructionSize} is checked both against the bytes one instruction consumed and
 * against the remainder left buffered. Checking only the remainder would let a megabyte-long
 * instruction through whenever it happened to arrive whole in one QUIC stream read; checking only
 * the consumed bytes would let an unterminated continuation run grow the buffer forever, since such
 * a run reads as "not yet whole" rather than as malformed.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.3">RFC 9204 §4.3 — Encoder
 * Instructions</a>
 */
public final class QpackEncoderStreamReader {
	private static final boolean CHECKS = Checks.isEnabled(QpackEncoderStreamReader.class);

	private final QpackDynamicTable table;
	private final long maxInstructionSize;

	private @Nullable ByteBuf pending;
	private long instructionsApplied;

	/**
	 * @param maxInstructionSize the {@code Http3Settings.qpackMaxInstructionSize()} bound, in bytes,
	 *                           above which an instruction is {@code QPACK_ENCODER_STREAM_ERROR}
	 *                           rather than buffered (FR-028, SI-3)
	 */
	public QpackEncoderStreamReader(QpackDynamicDecoder decoder, long maxInstructionSize) {
		if (CHECKS) checkArgument(maxInstructionSize > 0, "maxInstructionSize must be positive");
		this.table = decoder.table();
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
	 * @throws QpackException {@link Http3Errors#QPACK_ENCODER_STREAM_ERROR} at connection scope, for
	 *                        every cause of FR-029 and for the {@code maxInstructionSize} bound
	 */
	public int feed(ByteBuf buf) throws QpackException {
		pending = pending == null ? buf : ByteBufPool.append(pending, buf);
		int applied = 0;
		try {
			while (true) {
				int head = pending.head();
				EncoderInstruction instruction = QpackInstructions.readEncoderInstruction(pending);
				if (instruction == null) break;
				requireWithinBound(pending.head() - head);
				apply(instruction);
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
			throw error("a QPACK encoder-stream instruction above the configured maximum instruction size");
		}
	}

	private void apply(EncoderInstruction instruction) throws QpackException {
		if (instruction instanceof SetDynamicTableCapacity setCapacity) {
			long capacity = setCapacity.capacity();
			if (capacity > table.maxCapacity()) {
				// RFC 9204 §4.3.1: above the limit of §3.2.3 is a connection error, and the comparison
				// happens before the narrowing cast, so an out-of-int capacity cannot wrap into range.
				throw error("a Set Dynamic Table Capacity above the advertised maximum");
			}
			table.setCapacity((int) capacity);
		} else if (instruction instanceof InsertWithNameReference nameReference) {
			insert(nameReference);
		} else if (instruction instanceof InsertWithLiteralName literalName) {
			byte[] nameBytes = literalName.name();
			insert(QpackDynamicDecoder.internName(nameBytes), literalName.value(),
				QpackDynamicDecoder.hasUppercase(nameBytes));
		} else {
			long absoluteIndex = table.absoluteFromEncoderRelative(((Duplicate) instruction).index());
			if (!table.isAvailable(absoluteIndex)) {
				throw error("a Duplicate naming an evicted or never-inserted dynamic table entry");
			}
			if (table.duplicate(absoluteIndex) == QpackDynamicTable.NOT_INSERTED) {
				throw error("a Duplicate whose copy exceeds the current dynamic table capacity");
			}
		}
	}

	private void insert(InsertWithNameReference nameReference) throws QpackException {
		if (nameReference.staticTable()) {
			long index = nameReference.nameIndex();
			if (index >= QpackStaticTable.SIZE) {
				// RFC 9204 §3.1, on the encoder stream rather than in a field section.
				throw error("an Insert With Name Reference naming a static table index out of range");
			}
			insert(QpackStaticTable.name((int) index), nameReference.value(), false);
			return;
		}
		long absoluteIndex = table.absoluteFromEncoderRelative(nameReference.nameIndex());
		if (!table.isAvailable(absoluteIndex)) {
			throw error("an Insert With Name Reference naming an evicted or never-inserted dynamic table entry");
		}
		insert(table.nameAt(absoluteIndex), nameReference.value(), table.nameHadUppercaseAt(absoluteIndex));
	}

	/**
	 * A decoder table carries no reference counts, so {@code NOT_INSERTED} has exactly one meaning
	 * here: the entry alone exceeds the current capacity (RFC 9204 §3.2.2). That is the encoder's
	 * error, not a local shortage — the two tables would silently disagree from here on.
	 */
	private void insert(HttpHeader name, byte[] value, boolean nameHadUppercase) throws QpackException {
		if (table.insert(name, value, nameHadUppercase) == QpackDynamicTable.NOT_INSERTED) {
			throw error("an insertion whose entry size exceeds the current dynamic table capacity");
		}
	}

	private static QpackException error(String reason) {
		return QpackException.connectionError(Http3Errors.QPACK_ENCODER_STREAM_ERROR, reason);
	}
}
