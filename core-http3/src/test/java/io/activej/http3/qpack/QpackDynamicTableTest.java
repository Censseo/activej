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

import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import org.junit.Test;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static io.activej.http3.qpack.QpackDynamicTable.*;
import static org.junit.Assert.*;

/**
 * The RFC 9204 §3.2 dynamic table: insertion, size accounting, FIFO eviction, absolute-index
 * stability, capacity changes and the three index conversions.
 * <p>
 * No {@code @ClassRule}: the table is a plain synchronous structure holding heap arrays — no
 * {@code ByteBuf}, no codegen, no reactor (research D-1), so it is tested exactly like
 * {@link QpackStaticTableTest}.
 */
public class QpackDynamicTableTest {
	/** Every fixture entry is {@code 3 + 1 + 32 = 36} bytes, so capacities read as entry counts. */
	private static final int ENTRY_BYTES = 3 + 1 + ENTRY_OVERHEAD;

	private static final int SECTIONS = 8;

	private static HttpHeader name(String name) {
		return HttpHeaders.of(name);
	}

	private static byte[] value(String value) {
		return encodeAscii(value);
	}

	private static QpackField field(String name, String value) {
		return new QpackField(name(name), value(value));
	}

	/** A capacity that holds exactly {@code entries} fixture entries and not one byte more. */
	private static int cap(int entries) {
		return entries * ENTRY_BYTES;
	}

	private static QpackDynamicTable filled(int capacityEntries, String... names) {
		QpackDynamicTable table = QpackDynamicTable.forEncoder(cap(capacityEntries), SECTIONS);
		for (int i = 0; i < names.length; i++) {
			table.insert(name(names[i]), value(String.valueOf(i)));
		}
		return table;
	}

	@Test
	public void insertReturnsSuccessiveAbsoluteIndicesFromZero() {
		QpackDynamicTable table = QpackDynamicTable.forEncoder(cap(4), SECTIONS);
		assertEquals(0, table.insert(name("aaa"), value("0")));
		assertEquals(1, table.insert(name("bbb"), value("1")));
		assertEquals(2, table.insert(name("ccc"), value("2")));
		assertEquals(3, table.insertCount());
		assertEquals(3, table.entryCount());
		assertEquals(0, table.droppedCount());
	}

	@Test
	public void sizeIsNamePlusValuePlusThirtyTwo() {
		assertEquals(32, ENTRY_OVERHEAD);
		assertEquals(36, entrySize(name("aaa"), value("0")));
		assertEquals(36, entrySize(field("aaa", "0")));
		assertEquals(3 + 5 + 32, entrySize(name("abc"), value("hello")));

		QpackDynamicTable table = QpackDynamicTable.forEncoder(cap(4), SECTIONS);
		assertEquals(0, table.size());
		table.insert(name("aaa"), value("0"));
		assertEquals(36, table.size());
		table.insert(name("abc"), value("hello"));
		assertEquals(36 + 40, table.size());
	}

	@Test
	public void maxEntriesIsFloorOfMaxCapacityOverThirtyTwo() {
		QpackDynamicTable table = QpackDynamicTable.forEncoder(200, SECTIONS);
		assertEquals(200, table.maxCapacity());
		assertEquals(6, table.maxEntries());

		assertTrue(table.setCapacity(64));
		assertEquals(64, table.capacity());
		assertEquals("MaxEntries derives from the advertised maximum, not the current capacity",
			6, table.maxEntries());
	}

	@Test
	public void evictsFromTheTailUntilTheNewEntryFits() {
		QpackDynamicTable table = filled(2, "aaa", "bbb");
		assertEquals(cap(2), table.size());

		assertEquals(2, table.insert(name("ccc"), value("2")));
		assertFalse(table.isAvailable(0));
		assertTrue(table.isAvailable(1));
		assertTrue(table.isAvailable(2));
		assertEquals(2, table.entryCount());
		assertTrue(table.size() <= table.capacity());
	}

	@Test
	public void absoluteIndicesAreStableAcrossEviction() {
		QpackDynamicTable table = filled(2, "aaa", "bbb");
		table.insert(name("ccc"), value("2"));

		assertEquals(name("bbb"), table.nameAt(1));
		assertArrayEquals(value("1"), table.valueAt(1));
		assertEquals(name("ccc"), table.nameAt(2));
		assertArrayEquals(value("2"), table.valueAt(2));
		assertEquals(3, table.insertCount());
		assertEquals(1, table.droppedCount());

		table.insert(name("ddd"), value("3"));
		assertEquals(name("ccc"), table.nameAt(2));
		assertEquals(4, table.insertCount());
		assertEquals(2, table.droppedCount());
	}

	@Test
	public void evictedIndexIsUnavailable() {
		QpackDynamicTable table = filled(2, "aaa", "bbb");
		table.insert(name("ccc"), value("2"));

		assertFalse(table.isAvailable(0));
		assertThrows(IllegalArgumentException.class, () -> table.nameAt(0));
		assertThrows(IllegalArgumentException.class, () -> table.valueAt(0));
		assertFalse(table.isAvailable(3));
		assertThrows(IllegalArgumentException.class, () -> table.nameAt(3));
	}

	@Test
	public void refusesAnEntryLargerThanTheWholeCapacity() {
		QpackDynamicTable table = filled(2, "aaa");
		byte[] huge = new byte[cap(2)];

		assertFalse(table.fits(entrySize(name("bbb"), huge)));
		assertEquals(NOT_INSERTED, table.insert(name("bbb"), huge));
		assertEquals(cap(1), table.size());
		assertEquals(1, table.insertCount());
		assertEquals(1, table.entryCount());
	}

	@Test
	public void refusedOverCapacityInsertEvictsNothing() {
		QpackDynamicTable table = filled(2, "aaa", "bbb");
		byte[] huge = new byte[cap(2)];

		assertEquals(NOT_INSERTED, table.insert(name("ccc"), huge));
		assertTrue(table.isAvailable(0));
		assertTrue(table.isAvailable(1));
		assertEquals(2, table.entryCount());
		assertEquals(cap(2), table.size());
		assertEquals(2, table.insertCount());
		assertEquals(0, table.droppedCount());
	}

	@Test
	public void capacityReductionEvictsImmediately() {
		QpackDynamicTable table = filled(3, "aaa", "bbb", "ccc");

		assertTrue(table.setCapacity(cap(1)));
		assertEquals(cap(1), table.capacity());
		assertEquals(cap(1), table.size());
		assertEquals(1, table.entryCount());
		assertTrue(table.isAvailable(2));
		assertFalse(table.isAvailable(1));
	}

	@Test
	public void capacityZeroEmptiesTheTableButNotTheInsertCount() {
		QpackDynamicTable table = filled(3, "aaa", "bbb", "ccc");

		assertTrue(table.setCapacity(0));
		assertEquals(0, table.capacity());
		assertEquals(0, table.size());
		assertEquals(0, table.entryCount());
		assertEquals(3, table.insertCount());
		assertEquals(3, table.droppedCount());
		assertEquals(NOT_INSERTED, table.insert(name("ddd"), value("3")));

		assertTrue(table.setCapacity(cap(2)));
		assertEquals("absolute indices resume where they left off", 3,
			table.insert(name("ddd"), value("3")));
		assertEquals(4, table.insertCount());
	}

	@Test
	public void capacityAboveMaxCapacityIsRejected() {
		QpackDynamicTable table = QpackDynamicTable.forEncoder(cap(2), SECTIONS);
		assertThrows(IllegalArgumentException.class, () -> table.setCapacity(cap(2) + 1));
		assertThrows(IllegalArgumentException.class, () -> table.setCapacity(-1));
		assertEquals(cap(2), table.capacity());
	}

	@Test
	public void relativeIndexResolvesAgainstBase() {
		assertEquals(2, absoluteFromRelative(0, 3));
		assertEquals(1, absoluteFromRelative(1, 3));
		assertEquals(0, absoluteFromRelative(2, 3));
	}

	@Test
	public void postBaseIndexResolvesAgainstBase() {
		assertEquals(3, absoluteFromPostBase(0, 3));
		assertEquals(4, absoluteFromPostBase(1, 3));
		assertEquals(0, absoluteFromPostBase(0, 0));
	}

	@Test
	public void encoderStreamRelativeIndexResolvesAgainstInsertCount() {
		QpackDynamicTable table = filled(4, "aaa", "bbb", "ccc");
		assertEquals(2, table.absoluteFromEncoderRelative(0));
		assertEquals(0, table.absoluteFromEncoderRelative(2));
		assertEquals(-1, table.absoluteFromEncoderRelative(3));
	}

	@Test
	public void underflowingRelativeIndexIsNegativeNotAnException() {
		assertEquals(-1, absoluteFromRelative(0, 0));
		assertTrue(absoluteFromRelative(5, 3) < 0);
		assertTrue(absoluteFromRelative(1L << 62, 3) < 0);

		QpackDynamicTable table = filled(4, "aaa");
		assertFalse(table.isAvailable(absoluteFromRelative(5, 3)));
		assertFalse(table.isAvailable(-1));
	}

	@Test
	public void duplicateAppendsACopyAtANewIndex() {
		QpackDynamicTable table = filled(2, "aaa", "bbb");

		long copy = table.duplicate(0);
		assertEquals(2, copy);
		assertEquals(name("aaa"), table.nameAt(copy));
		assertArrayEquals(value("0"), table.valueAt(copy));
		assertEquals("a Duplicate may legally evict the very entry it copies",
			false, table.isAvailable(0));
		assertEquals(3, table.insertCount());
		assertEquals(2, table.entryCount());
	}

	@Test
	public void duplicateOfAnEvictedIndexIsRefused() {
		QpackDynamicTable table = filled(2, "aaa", "bbb", "ccc");
		assertFalse(table.isAvailable(0));

		assertEquals(NOT_INSERTED, table.duplicate(0));
		assertEquals(NOT_INSERTED, table.duplicate(-1));
		assertEquals(NOT_INSERTED, table.duplicate(99));
		assertEquals(3, table.insertCount());
	}

	@Test
	public void findNameAndValueReturnsTheNewestMatch() {
		QpackDynamicTable table = QpackDynamicTable.forEncoder(cap(4), SECTIONS);
		table.insert(name("aaa"), value("0"));
		table.insert(name("bbb"), value("1"));
		table.insert(name("aaa"), value("0"));

		assertEquals(2, table.findNameAndValue(name("aaa"), value("0")));
		assertEquals(NOT_FOUND, table.findNameAndValue(name("aaa"), value("9")));
		assertEquals(NOT_FOUND, table.findNameAndValue(name("zzz"), value("0")));

		table.insert(name("ccc"), value("3"));
		table.insert(name("ddd"), value("4"));
		assertFalse(table.isAvailable(0));
		assertEquals("the evicted older copy must not unregister the newer one",
			2, table.findNameAndValue(name("aaa"), value("0")));

		table.insert(name("eee"), value("5"));
		table.insert(name("fff"), value("6"));
		assertFalse(table.isAvailable(2));
		assertEquals(NOT_FOUND, table.findNameAndValue(name("aaa"), value("0")));
	}

	@Test
	public void findNameReturnsTheNewestMatch() {
		QpackDynamicTable table = QpackDynamicTable.forEncoder(cap(4), SECTIONS);
		table.insert(name("aaa"), value("0"));
		table.insert(name("aaa"), value("1"));

		assertEquals(1, table.findName(name("aaa")));
		assertEquals(0, table.findNameAndValue(name("aaa"), value("0")));
		assertEquals(1, table.findNameAndValue(name("aaa"), value("1")));
		assertEquals(NOT_FOUND, table.findName(name("zzz")));

		table.insert(name("ccc"), value("2"));
		table.insert(name("ddd"), value("3"));
		table.insert(name("eee"), value("4"));
		assertEquals(1, table.findName(name("aaa")));

		table.insert(name("fff"), value("5"));
		assertFalse(table.isAvailable(1));
		assertEquals(NOT_FOUND, table.findName(name("aaa")));
	}

	@Test
	public void decoderTableRefusesEncoderOnlyLookups() {
		QpackDynamicTable table = QpackDynamicTable.forDecoder(cap(2));
		table.insert(name("aaa"), value("0"));

		assertEquals(name("aaa"), table.nameAt(0));
		assertThrows(IllegalStateException.class, () -> table.findName(name("aaa")));
		assertThrows(IllegalStateException.class, () -> table.findNameAndValue(name("aaa"), value("0")));
	}
}
