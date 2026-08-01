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
import io.activej.http3.Http3Errors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 7541 Appendix B Huffman coding, as used by QPACK (RFC 9204 §4.1.2) for field name and value
 * strings.
 * <p>
 * {@link #encode} always Huffman-codes what it is given; the caller (here,
 * {@link QpackStaticEncoder}) is expected to compare {@link #encodedLength} against the literal
 * length and only use the Huffman form when it is shorter (RFC 7541 §5.2 permits either).
 * {@link #decode} pushes each decoded octet to a caller-supplied sink as it is produced rather than
 * returning a buffered result, so a caller accounting decoded bytes against a size bound (QPACK's
 * field-section limit, which Huffman can expand past by roughly 2×) can abort before more than the
 * bound has ever been materialized — see contracts/wire-protocol.md §4.3.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#appendix-B">RFC 7541 Appendix B — Huffman
 * Code</a>
 */
public final class QpackHuffman {
	private QpackHuffman() {}

	/** The synthetic 257th symbol: never a decoded octet, only ever the shape of the trailing padding. */
	private static final int EOS = 256;

	// RFC 7541 Appendix B, symbols 0-255 plus EOS=256. Validated by: (1) parsing directly from
	// the RFC's raw .txt table (bits/hex/len self-consistent for all 257 rows), (2) re-deriving
	// every code from lengths alone via canonical Huffman construction (0 mismatches against the
	// RFC's own hex values), (3) round-trip against the RFC 7541 C.4.1 vector: Huffman-encoding
	// "www.example.com" produces exactly f1e3c2e5f23a6ba0ab90f4ff, (4) Kraft's inequality sums to
	// exactly 1 (a complete, valid prefix-free code).
	private static final int[] CODE_LENGTHS = {
		13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28,
		28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28,
		6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6,
		5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10,
		13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
		7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6,
		15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5,
		6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28,
		20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23,
		24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
		22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23,
		21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
		26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25,
		19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27,
		20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
		26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26,
		30,
	};

	private static final int[] CODES = {
		0x1ff8, 0x7fffd8, 0xfffffe2, 0xfffffe3, 0xfffffe4, 0xfffffe5, 0xfffffe6, 0xfffffe7, 0xfffffe8, 0xffffea, 0x3ffffffc, 0xfffffe9, 0xfffffea, 0x3ffffffd, 0xfffffeb, 0xfffffec,
		0xfffffed, 0xfffffee, 0xfffffef, 0xffffff0, 0xffffff1, 0xffffff2, 0x3ffffffe, 0xffffff3, 0xffffff4, 0xffffff5, 0xffffff6, 0xffffff7, 0xffffff8, 0xffffff9, 0xffffffa, 0xffffffb,
		0x14, 0x3f8, 0x3f9, 0xffa, 0x1ff9, 0x15, 0xf8, 0x7fa, 0x3fa, 0x3fb, 0xf9, 0x7fb, 0xfa, 0x16, 0x17, 0x18,
		0x0, 0x1, 0x2, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x5c, 0xfb, 0x7ffc, 0x20, 0xffb, 0x3fc,
		0x1ffa, 0x21, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
		0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70, 0x71, 0x72, 0xfc, 0x73, 0xfd, 0x1ffb, 0x7fff0, 0x1ffc, 0x3ffc, 0x22,
		0x7ffd, 0x3, 0x23, 0x4, 0x24, 0x5, 0x25, 0x26, 0x27, 0x6, 0x74, 0x75, 0x28, 0x29, 0x2a, 0x7,
		0x2b, 0x76, 0x2c, 0x8, 0x9, 0x2d, 0x77, 0x78, 0x79, 0x7a, 0x7b, 0x7ffe, 0x7fc, 0x3ffd, 0x1ffd, 0xffffffc,
		0xfffe6, 0x3fffd2, 0xfffe7, 0xfffe8, 0x3fffd3, 0x3fffd4, 0x3fffd5, 0x7fffd9, 0x3fffd6, 0x7fffda, 0x7fffdb, 0x7fffdc, 0x7fffdd, 0x7fffde, 0xffffeb, 0x7fffdf,
		0xffffec, 0xffffed, 0x3fffd7, 0x7fffe0, 0xffffee, 0x7fffe1, 0x7fffe2, 0x7fffe3, 0x7fffe4, 0x1fffdc, 0x3fffd8, 0x7fffe5, 0x3fffd9, 0x7fffe6, 0x7fffe7, 0xffffef,
		0x3fffda, 0x1fffdd, 0xfffe9, 0x3fffdb, 0x3fffdc, 0x7fffe8, 0x7fffe9, 0x1fffde, 0x7fffea, 0x3fffdd, 0x3fffde, 0xfffff0, 0x1fffdf, 0x3fffdf, 0x7fffeb, 0x7fffec,
		0x1fffe0, 0x1fffe1, 0x3fffe0, 0x1fffe2, 0x7fffed, 0x3fffe1, 0x7fffee, 0x7fffef, 0xfffea, 0x3fffe2, 0x3fffe3, 0x3fffe4, 0x7ffff0, 0x3fffe5, 0x3fffe6, 0x7ffff1,
		0x3ffffe0, 0x3ffffe1, 0xfffeb, 0x7fff1, 0x3fffe7, 0x7ffff2, 0x3fffe8, 0x1ffffec, 0x3ffffe2, 0x3ffffe3, 0x3ffffe4, 0x7ffffde, 0x7ffffdf, 0x3ffffe5, 0xfffff1, 0x1ffffed,
		0x7fff2, 0x1fffe3, 0x3ffffe6, 0x7ffffe0, 0x7ffffe1, 0x3ffffe7, 0x7ffffe2, 0xfffff2, 0x1fffe4, 0x1fffe5, 0x3ffffe8, 0x3ffffe9, 0xffffffd, 0x7ffffe3, 0x7ffffe4, 0x7ffffe5,
		0xfffec, 0xfffff3, 0xfffed, 0x1fffe6, 0x3fffe9, 0x1fffe7, 0x1fffe8, 0x7ffff3, 0x3fffea, 0x3fffeb, 0x1ffffee, 0x1ffffef, 0xfffff4, 0xfffff5, 0x3ffffea, 0x7ffff4,
		0x3ffffeb, 0x7ffffe6, 0x3ffffec, 0x3ffffed, 0x7ffffe7, 0x7ffffe8, 0x7ffffe9, 0x7ffffea, 0x7ffffeb, 0xffffffe, 0x7ffffec, 0x7ffffed, 0x7ffffee, 0x7ffffef, 0x7fffff0, 0x3ffffee,
		0x3fffffff,
	};

	/** One node of the decode trie built from {@link #CODES}/{@link #CODE_LENGTHS} at class-init. */
	private static final class Node {
		Node zero;
		Node one;
		int symbol = -1;
		int depth;
		/** Whether every bit on the path from the root to this node is 1 — an EOS-code prefix. */
		boolean allOnes;
		int stateId = -1;
	}

	// Nibble-at-a-time (4-bit) decode DFA, built once here from the trie above rather than
	// hand-written: research Decision 5 calls for a multi-bit-per-step transition table, not a
	// bit-at-a-time tree walk, but a hand-authored table for 257 variable-length codes is exactly
	// the kind of thing that silently drifts from the RFC. Deriving it programmatically from the
	// already-validated CODES/CODE_LENGTHS keeps the two representations from ever disagreeing.
	//
	// Every code in this table is at least 5 bits, so a single 4-bit step can complete at most one
	// symbol regardless of leftover bits carried in from the previous step — that invariant is what
	// keeps NEXT_STATE/EMIT_SYMBOL a simple flat array instead of needing to represent multiple
	// emissions per step.
	private static final int[] NEXT_STATE;
	private static final int[] EMIT_SYMBOL;
	private static final int[] STATE_DEPTH;
	private static final boolean[] STATE_ALL_ONES;

	static {
		Node root = new Node();
		root.depth = 0;
		root.allOnes = true;
		for (int symbol = 0; symbol < CODES.length; symbol++) {
			insert(root, symbol, CODES[symbol], CODE_LENGTHS[symbol]);
		}

		List<Node> states = new ArrayList<>();
		Map<Node, Integer> stateIds = new IdentityHashMap<>();
		Deque<Node> queue = new ArrayDeque<>();
		root.stateId = 0;
		states.add(root);
		stateIds.put(root, 0);
		queue.add(root);

		List<int[]> nextRows = new ArrayList<>();
		List<int[]> emitRows = new ArrayList<>();

		while (!queue.isEmpty()) {
			Node state = queue.poll();
			int[] nextRow = new int[16];
			int[] emitRow = new int[16];
			for (int nibble = 0; nibble < 16; nibble++) {
				Node cur = state;
				int emitted = -1;
				for (int bitPos = 3; bitPos >= 0; bitPos--) {
					int bit = (nibble >>> bitPos) & 1;
					cur = bit == 0 ? cur.zero : cur.one;
					if (cur == null) {
						throw new AssertionError("Incomplete Huffman code trie: RFC 7541 App. B is a complete prefix code");
					}
					if (cur.symbol != -1) {
						if (emitted != -1) {
							throw new AssertionError("More than one symbol completed within a single nibble step");
						}
						emitted = cur.symbol;
						cur = root;
					}
				}
				emitRow[nibble] = emitted;
				Integer nextId = stateIds.get(cur);
				if (nextId == null) {
					nextId = states.size();
					cur.stateId = nextId;
					stateIds.put(cur, nextId);
					states.add(cur);
					queue.add(cur);
				}
				nextRow[nibble] = nextId;
			}
			nextRows.add(nextRow);
			emitRows.add(emitRow);
		}

		int stateCount = states.size();
		NEXT_STATE = new int[stateCount * 16];
		EMIT_SYMBOL = new int[stateCount * 16];
		STATE_DEPTH = new int[stateCount];
		STATE_ALL_ONES = new boolean[stateCount];
		for (int s = 0; s < stateCount; s++) {
			System.arraycopy(nextRows.get(s), 0, NEXT_STATE, s * 16, 16);
			System.arraycopy(emitRows.get(s), 0, EMIT_SYMBOL, s * 16, 16);
			STATE_DEPTH[s] = states.get(s).depth;
			STATE_ALL_ONES[s] = states.get(s).allOnes;
		}
	}

	private static void insert(Node root, int symbol, int code, int length) {
		Node node = root;
		for (int i = length - 1; i >= 0; i--) {
			int bit = (code >>> i) & 1;
			Node next = bit == 0 ? node.zero : node.one;
			if (next == null) {
				next = new Node();
				next.depth = node.depth + 1;
				next.allOnes = node.allOnes && bit == 1;
				if (bit == 0) node.zero = next; else node.one = next;
			}
			node = next;
		}
		node.symbol = symbol;
	}

	/** The exact number of bytes {@link #encode} emits for {@code data[offset, offset+length)}. */
	public static int encodedLength(byte[] data, int offset, int length) {
		long bits = 0;
		for (int i = 0; i < length; i++) {
			bits += CODE_LENGTHS[data[offset + i] & 0xFF];
		}
		return (int) ((bits + 7) / 8);
	}

	/** Huffman-encodes {@code data[offset, offset+length)} into {@code out}, padded with 1-bits (an EOS prefix). */
	public static void encode(ByteBuf out, byte[] data, int offset, int length) {
		long bitBuffer = 0;
		int bitCount = 0;
		for (int i = 0; i < length; i++) {
			int symbol = data[offset + i] & 0xFF;
			int code = CODES[symbol];
			int codeLength = CODE_LENGTHS[symbol];
			bitBuffer = (bitBuffer << codeLength) | (code & ((1L << codeLength) - 1));
			bitCount += codeLength;
			while (bitCount >= 8) {
				bitCount -= 8;
				out.writeByte((byte) (bitBuffer >>> bitCount));
			}
			bitBuffer &= (1L << bitCount) - 1;
		}
		if (bitCount > 0) {
			int pad = 8 - bitCount;
			int lastByte = (int) ((bitBuffer << pad) | ((1 << pad) - 1));
			out.writeByte((byte) lastByte);
		}
	}

	/** Receives one decoded octet at a time, so a caller can bound total output without buffering it. */
	@FunctionalInterface
	public interface ByteSink {
		void accept(byte b) throws QpackException;
	}

	/**
	 * Decodes {@code data[offset, offset+length)}, pushing each decoded octet to {@code sink} as
	 * soon as it is known.
	 *
	 * @throws QpackException {@link Http3Errors#QPACK_DECOMPRESSION_FAILED} if an encoded EOS symbol
	 *                        appears as content, if the trailing padding is longer than 7 bits, or
	 *                        if it is not a prefix of the EOS code (i.e. not all 1-bits)
	 */
	public static void decode(byte[] data, int offset, int length, ByteSink sink) throws QpackException {
		int state = 0;
		for (int i = 0; i < length; i++) {
			int b = data[offset + i] & 0xFF;
			state = step(state, b >>> 4, sink);
			state = step(state, b & 0xF, sink);
		}
		if (STATE_DEPTH[state] > 7 || !STATE_ALL_ONES[state]) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"invalid Huffman padding at end of string");
		}
	}

	private static int step(int state, int nibble, ByteSink sink) throws QpackException {
		int idx = state * 16 + nibble;
		int symbol = EMIT_SYMBOL[idx];
		if (symbol != -1) {
			if (symbol == EOS) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"Huffman-encoded EOS symbol appeared as content");
			}
			sink.accept((byte) symbol);
		}
		return NEXT_STATE[idx];
	}
}
