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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static io.activej.http3.qpack.QpackDynamicTable.*;
import static org.junit.Assert.*;

/**
 * Encoder-side reference counting (spec FR-013) and the single release funnel (research D-3).
 * <p>
 * An entry a peer may still be decoding against must never be evicted, and the count that says so
 * must be decremented exactly once per outstanding reference. Over-releasing evicts an entry the
 * peer still references; under-releasing pins the table and silently degrades compression to
 * literals. Neither shows up as a crash, which is why {@link #singleDecrementSiteInSource()} asserts
 * the structural property directly rather than trusting the behavioural tests to cover every path.
 * <p>
 * No {@code @ClassRule}, for the same reason as {@link QpackDynamicTableTest}: nothing here touches a
 * {@code ByteBuf}, codegen or a reactor.
 */
public class QpackDynamicTableRefCountTest {
	private static final int ENTRY_BYTES = 3 + 1 + ENTRY_OVERHEAD;

	private static final long STREAM = 4;
	private static final long OTHER_STREAM = 8;

	private static HttpHeader name(String name) {
		return HttpHeaders.of(name);
	}

	private static byte[] value(String value) {
		return encodeAscii(value);
	}

	private static int cap(int entries) {
		return entries * ENTRY_BYTES;
	}

	/** A table of exactly {@code capacityEntries} slots, filled with {@code entries} of them. */
	private static QpackDynamicTable filled(int capacityEntries, int maxSections, int entries) {
		QpackDynamicTable table = QpackDynamicTable.forEncoder(cap(capacityEntries), maxSections);
		for (int i = 0; i < entries; i++) {
			table.insert(name("ex" + (char) ('a' + i)), value(String.valueOf(i)));
		}
		return table;
	}

	@Test
	public void referencedEntryIsNeverEvicted() {
		QpackDynamicTable table = filled(2, 8, 2);
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));
		assertEquals(1, table.referenceCountOf(0));

		assertEquals(NOT_INSERTED, table.insert(name("zzz"), value("9")));
		assertTrue(table.isAvailable(0));
		assertTrue(table.isAvailable(1));
		assertEquals(2, table.entryCount());
		assertEquals(cap(2), table.size());
		assertEquals(2, table.insertCount());
		assertEquals(0, table.droppedCount());
	}

	@Test
	public void evictionStopsAtTheFirstReferencedEntry() {
		QpackDynamicTable table = filled(3, 8, 3);
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));
		assertEquals(0, table.referenceCountOf(1));
		assertEquals(0, table.referenceCountOf(2));

		assertEquals("entries 1 and 2 are free, but the FIFO cannot reach past a pinned entry 0",
			NOT_INSERTED, table.insert(name("zzz"), value("9")));
		assertTrue(table.isAvailable(0));
		assertTrue(table.isAvailable(1));
		assertTrue(table.isAvailable(2));
		assertEquals(3, table.entryCount());
	}

	@Test
	public void sectionAcknowledgmentReleases() {
		QpackDynamicTable table = filled(2, 8, 2);
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));
		assertEquals(NOT_INSERTED, table.insert(name("zzz"), value("9")));

		assertEquals(1, table.acknowledgeSection(STREAM));
		assertEquals(0, table.referenceCountOf(0));
		assertEquals(0, table.outstandingSectionCount());

		assertEquals(2, table.insert(name("zzz"), value("9")));
		assertFalse(table.isAvailable(0));
		assertEquals(2, table.entryCount());
	}

	@Test
	public void streamCancellationReleasesEverySectionOnThatStream() {
		QpackDynamicTable table = filled(4, 8, 2);
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));
		assertTrue(table.trackSection(STREAM, 2, new long[]{1}));
		assertEquals(2, table.outstandingSectionCount());

		assertEquals(2, table.cancelStream(STREAM));
		assertEquals(0, table.referenceCountOf(0));
		assertEquals(0, table.referenceCountOf(1));
		assertEquals(0, table.outstandingSectionCount());
	}

	@Test
	public void connectionCloseReleasesEverything() {
		QpackDynamicTable table = filled(4, 8, 4);
		assertTrue(table.trackSection(0, 1, new long[]{0}));
		assertTrue(table.trackSection(STREAM, 2, new long[]{1}));
		assertTrue(table.trackSection(OTHER_STREAM, 4, new long[]{2, 3}));
		assertEquals(3, table.outstandingSectionCount());

		assertEquals(3, table.releaseAll());
		for (int i = 0; i < 4; i++) {
			assertEquals("entry " + i, 0, table.referenceCountOf(i));
		}
		assertEquals(0, table.outstandingSectionCount());
	}

	@Test
	public void sectionsOnOneStreamAreAcknowledgedOldestFirst() {
		QpackDynamicTable table = filled(4, 8, 2);
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));
		assertTrue(table.trackSection(STREAM, 2, new long[]{1}));

		assertEquals("RFC 9204 §4.4.1 acknowledges the oldest outstanding section on the stream",
			1, table.acknowledgeSection(STREAM));
		assertEquals(0, table.referenceCountOf(0));
		assertEquals(1, table.referenceCountOf(1));
		assertEquals(1, table.outstandingSectionCount());

		assertEquals(2, table.acknowledgeSection(STREAM));
		assertEquals(0, table.referenceCountOf(1));
		assertEquals(0, table.outstandingSectionCount());
	}

	@Test
	public void acknowledgingTwiceIsRefused() {
		QpackDynamicTable table = filled(4, 8, 1);
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));

		assertEquals(1, table.acknowledgeSection(STREAM));
		assertEquals(NO_SECTION, table.acknowledgeSection(STREAM));
		assertEquals(0, table.referenceCountOf(0));
		assertEquals(0, table.outstandingSectionCount());
	}

	@Test
	public void cancelAfterAcknowledgeReleasesNothingMore() {
		QpackDynamicTable table = filled(4, 8, 1);
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));
		assertEquals(1, table.acknowledgeSection(STREAM));

		assertEquals(0, table.cancelStream(STREAM));
		assertEquals(0, table.referenceCountOf(0));
		assertEquals(0, table.outstandingSectionCount());
	}

	@Test
	public void releaseAllAfterCancelReleasesNothingMore() {
		QpackDynamicTable table = filled(4, 8, 1);
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));
		assertEquals(1, table.cancelStream(STREAM));

		assertEquals(0, table.releaseAll());
		assertEquals(0, table.referenceCountOf(0));
		assertEquals(0, table.outstandingSectionCount());
	}

	@Test
	public void sectionWithNoDynamicReferencesIsNotTracked() {
		QpackDynamicTable table = filled(4, 8, 1);

		assertTrue(table.trackSection(STREAM, 0, new long[0]));
		assertEquals(0, table.outstandingSectionCount());
		assertEquals("RFC 9204 §4.4.1 acknowledges only a non-zero Required Insert Count",
			NO_SECTION, table.acknowledgeSection(STREAM));
	}

	@Test
	public void outstandingSectionBoundRefusesTracking() {
		QpackDynamicTable table = filled(4, 2, 2);

		assertTrue(table.canTrackSection());
		assertTrue(table.trackSection(STREAM, 1, new long[]{0}));
		assertTrue(table.canTrackSection());
		assertTrue(table.trackSection(STREAM, 2, new long[]{1}));

		assertFalse(table.canTrackSection());
		assertFalse(table.trackSection(OTHER_STREAM, 1, new long[]{0}));
		assertEquals("a refused section must not touch a single reference count",
			1, table.referenceCountOf(0));
		assertEquals(1, table.referenceCountOf(1));
		assertEquals(2, table.outstandingSectionCount());
	}

	@Test
	public void decoderTableRefusesSectionTracking() {
		QpackDynamicTable table = QpackDynamicTable.forDecoder(cap(2));
		table.insert(name("exa"), value("0"));

		assertThrows(IllegalStateException.class, table::canTrackSection);
		assertThrows(IllegalStateException.class, () -> table.trackSection(STREAM, 1, new long[]{0}));
		assertThrows(IllegalStateException.class, () -> table.acknowledgeSection(STREAM));
		assertThrows(IllegalStateException.class, () -> table.cancelStream(STREAM));
		assertThrows(IllegalStateException.class, table::releaseAll);
	}

	/**
	 * Research D-3 made mechanical: the reference count is decremented from exactly one place in the
	 * source. The behavioural tests above can only show that the paths they exercise are balanced; a
	 * second decrement site added later would keep them all green and still double-release.
	 * <p>
	 * Reads the checked-out source rather than bytecode, like {@code Http3PackageSeamTest} — Surefire
	 * runs with the module directory as its working directory.
	 */
	@Test
	public void singleDecrementSiteInSource() throws IOException {
		Pattern decrement = Pattern.compile("\\brefCount\\s*(--|-=)|--\\s*\\w*\\.?refCount\\b");
		Path source = Path.of("src/main/java/io/activej/http3/qpack/QpackDynamicTable.java");
		assertTrue("expected source to exist: " + source.toAbsolutePath(), Files.isRegularFile(source));

		long decrements = Files.readAllLines(source).stream()
			.filter(line -> decrement.matcher(line).find())
			.count();
		assertEquals("research D-3: refCount must be decremented from exactly one funnel",
			1, decrements);
	}
}
