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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T146 — the spec's performance table requires that {@link QpackDynamicTable#findName} and
 * {@link QpackDynamicTable#findNameAndValue} be <b>O(1) average</b>, "not a linear scan": the encoder
 * asks both, once per field line, and a scan over a 4 KB table would be a measurable regression
 * against {@link QpackStaticEncoder}'s array lookup — the phase-1 encoder every dynamic connection is
 * compared to.
 * <p>
 * Reading the source and concluding "it is a {@link HashMap}" is not what this asserts. Three
 * independent things are:
 * <ol>
 *     <li><b>structural</b> — the two indices are hash maps holding exactly one entry per available
 *     table entry, so they are a maintained index rather than a cache in front of a scan;</li>
 *     <li><b>textual</b> — neither lookup method's body contains a loop, in the same spirit as
 *     {@code QpackDynamicTableRefCountTest.singleDecrementSiteInSource};</li>
 *     <li><b>empirical</b> — lookup cost is flat as the table grows tenfold, while a deliberately
 *     linear control over the same entries is orders of magnitude slower, and a million lookups over
 *     a 20 000-entry table finish in well under a second.</li>
 * </ol>
 * The third is the one that would catch a future rewrite that keeps the field names and loses the
 * complexity.
 * <p>
 * No {@code @ClassRule}: like {@link QpackDynamicTableTest}, nothing here touches a {@code ByteBuf},
 * codegen or a reactor.
 */
public class QpackDynamicTableLookupComplexityTest {
	/** Roughly what a 4 KB table holds — the size the spec's regression claim is about. */
	private static final int SMALL_ENTRIES = 256;

	/** Ten times {@link #SMALL_ENTRIES}: a linear scan costs ten times more here, a hash lookup does not. */
	private static final int LARGE_ENTRIES = 2_560;

	/** Large enough that a linear scan could not finish the budget below in any amount of time worth waiting. */
	private static final int HUGE_ENTRIES = 20_000;

	private static final int LOOKUPS = 200_000;

	/**
	 * Far fewer, because each one costs {@link #LARGE_ENTRIES}/2 comparisons — the control is there to
	 * calibrate the machine, not to be waited on. The result is normalised per lookup either way.
	 */
	private static final int LINEAR_LOOKUPS = 5_000;

	private static final int BUDGET_LOOKUPS = 1_000_000;
	private static final int REPEATS = 7;

	/**
	 * Cost at ten times the entries, over cost at {@link #SMALL_ENTRIES}. A hash lookup scores about 1
	 * (cache locality is the only thing that changes); a linear scan scores about 10.
	 */
	private static final double FLAT_FACTOR = 4.0;

	/**
	 * How much faster than the linear control a lookup must be at {@link #LARGE_ENTRIES}. The real
	 * factor is in the hundreds — this is a floor chosen to survive a loaded build machine, not an
	 * estimate.
	 */
	private static final double LINEAR_MARGIN = 10.0;

	/** A million lookups is ~0.05 s hashed and hours scanned; anything in between is still a pass. */
	private static final long BUDGET_MILLIS = 20_000;

	// region structural

	/**
	 * Both lookups are backed by a hash index, and each index holds exactly one entry per available
	 * table entry — which is what distinguishes a maintained index from a memo in front of a scan, and
	 * what {@code evictOldest} has to keep true.
	 */
	@Test
	public void bothLookupsAreBackedByAHashIndexOverEveryAvailableEntry() throws ReflectiveOperationException {
		Fixture fixture = Fixture.of(SMALL_ENTRIES);

		for (String fieldName : new String[]{"newestByName", "newestByNameValue"}) {
			Object index = read(fixture.table, fieldName);
			assertTrue(fieldName + " must be a java.util.Map, was " + index.getClass(),
				index instanceof Map);
			assertTrue(fieldName + " must be hash-based for an O(1) average lookup, was " +
					   index.getClass().getName(),
				index instanceof HashMap);
			assertEquals(fieldName + " must index every available entry, not memoize a scan",
				SMALL_ENTRIES, ((Map<?, ?>) index).size());
		}
	}

	/** A decoder table answers neither lookup, so it carries neither index — nothing to keep, nothing to scan. */
	@Test
	public void aDecoderTableCarriesNeitherIndex() throws ReflectiveOperationException {
		QpackDynamicTable table = QpackDynamicTable.forDecoder(4096);
		assertNull(read(table, "newestByName"));
		assertNull(read(table, "newestByNameValue"));
	}

	/**
	 * Neither lookup method's body contains a loop. Structural rather than behavioural on purpose: a
	 * scan reintroduced inside one of them would still pass every correctness test in this package and
	 * only show up as a slow connection under load.
	 */
	@Test
	public void neitherLookupMethodLoopsInSource() throws IOException {
		Path source = Path.of("src/main/java/io/activej/http3/qpack/QpackDynamicTable.java");
		assertTrue("expected source to exist: " + source.toAbsolutePath(), Files.isRegularFile(source));
		List<String> lines = Files.readAllLines(source);

		for (String signature : new String[]{"public long findName(", "public long findNameAndValue("}) {
			for (String line : methodBody(lines, signature)) {
				assertTrue(signature + " must not scan: found a loop in `" + line.trim() + '`',
					!line.contains("for (") && !line.contains("while (") && !line.contains(".stream()"));
			}
		}
	}

	// endregion
	// region empirical

	/**
	 * Ten times the entries, the same cost. A linear scan measured over the very same entries in the
	 * very same run is the control: it is what the cost would look like if the index were not there,
	 * so a machine too loaded to measure anything would fail the control rather than pass the claim.
	 */
	@Test
	public void lookupCostIsFlatAsTheTableGrowsTenfold() {
		Fixture small = Fixture.of(SMALL_ENTRIES);
		Fixture large = Fixture.of(LARGE_ENTRIES);

		// Warm up every shape before any of them is measured, so the first measurement is not the
		// interpreter's and the last is not the only compiled one.
		warmUpIndex(small);
		warmUpIndex(large);
		warmUpScan(large);

		double smallByName = nanosPerLookup(small::findNameLookups, LOOKUPS);
		double largeByName = nanosPerLookup(large::findNameLookups, LOOKUPS);
		double largeScanByName = nanosPerLookup(large::linearFindNameLookups, LINEAR_LOOKUPS);

		double smallByNameValue = nanosPerLookup(small::findNameAndValueLookups, LOOKUPS);
		double largeByNameValue = nanosPerLookup(large::findNameAndValueLookups, LOOKUPS);
		double largeScanByNameValue = nanosPerLookup(large::linearFindNameAndValueLookups, LINEAR_LOOKUPS);

		System.out.printf(Locale.ROOT,
			"QPACK dynamic table lookup, ns/op: findName %.1f @%d, %.1f @%d (linear scan %.1f); " +
			"findNameAndValue %.1f @%d, %.1f @%d (linear scan %.1f)%n",
			smallByName, SMALL_ENTRIES, largeByName, LARGE_ENTRIES, largeScanByName,
			smallByNameValue, SMALL_ENTRIES, largeByNameValue, LARGE_ENTRIES, largeScanByNameValue);

		assertFlat("findName", smallByName, largeByName, largeScanByName);
		assertFlat("findNameAndValue", smallByNameValue, largeByNameValue, largeScanByNameValue);
	}

	/**
	 * The claim without a ratio in it: a million lookups by name and a million by name and value, over
	 * a table of 20 000 entries, inside a budget a linear scan could not meet in any amount of time
	 * worth waiting for — 2 × 10<sup>10</sup> comparisons against the ~0.05 s this actually takes.
	 * The JUnit timeout is what turns "would take hours" into a failure rather than a hung build.
	 */
	@Test(timeout = 120_000)
	public void aMillionLookupsOverATwentyThousandEntryTableFitInTheBudget() {
		Fixture fixture = Fixture.of(HUGE_ENTRIES);
		warmUpIndex(fixture);

		long start = System.nanoTime();
		long checksum = fixture.findNameLookups(BUDGET_LOOKUPS) + fixture.findNameAndValueLookups(BUDGET_LOOKUPS);
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

		assertTrue("the loop must not be optimised away", checksum > 0);
		assertTrue(2 * BUDGET_LOOKUPS + " lookups over a " + HUGE_ENTRIES + "-entry table took " +
				   elapsedMillis + " ms, over the " + BUDGET_MILLIS + " ms budget — a linear scan would " +
				   "be " + HUGE_ENTRIES / 2 + " times slower than an index and cannot meet it",
			elapsedMillis < BUDGET_MILLIS);
	}

	// endregion
	// region harness

	private static void assertFlat(String lookup, double small, double large, double linearScan) {
		assertTrue(lookup + " cost grew from " + String.format(Locale.ROOT, "%.1f", small) + " ns at " +
				   SMALL_ENTRIES + " entries to " + String.format(Locale.ROOT, "%.1f", large) + " ns at " +
				   LARGE_ENTRIES + " — a tenfold table must not cost tenfold",
			large <= small * FLAT_FACTOR);
		assertTrue(lookup + " at " + LARGE_ENTRIES + " entries cost " +
				   String.format(Locale.ROOT, "%.1f", large) + " ns against a linear scan's " +
				   String.format(Locale.ROOT, "%.1f", linearScan) + " ns over the same entries — that is " +
				   "not the margin an index gives",
			large * LINEAR_MARGIN <= linearScan);
	}

	private static void warmUpIndex(Fixture fixture) {
		int lookups = Math.max(LOOKUPS / 10, fixture.names.size());
		keepAlive(fixture.findNameLookups(lookups) + fixture.findNameAndValueLookups(lookups));
	}

	private static void warmUpScan(Fixture fixture) {
		keepAlive(fixture.linearFindNameLookups(LINEAR_LOOKUPS) +
				  fixture.linearFindNameAndValueLookups(LINEAR_LOOKUPS));
	}

	/**
	 * The <b>minimum</b> over {@link #REPEATS} runs, not the mean: the minimum is the run least
	 * disturbed by the rest of the build, and a scheduler that steals time can only make a measurement
	 * larger.
	 */
	private static double nanosPerLookup(Lookups workload, int lookups) {
		long best = Long.MAX_VALUE;
		for (int repeat = 0; repeat < REPEATS; repeat++) {
			long start = System.nanoTime();
			long checksum = workload.run(lookups);
			long elapsed = System.nanoTime() - start;
			keepAlive(checksum);
			best = Math.min(best, elapsed);
		}
		return (double) best / lookups;
	}

	/** Consumes a checksum so the loop that produced it cannot be optimised away. */
	private static void keepAlive(long checksum) {
		if (checksum == Long.MIN_VALUE) throw new AssertionError("unreachable; keeps the loop alive");
	}

	private interface Lookups {
		long run(int lookups);
	}

	/** A filled encoder table plus the names and values it was filled from, so a control can scan them. */
	private static final class Fixture {
		final QpackDynamicTable table;
		final List<HttpHeader> names;
		final List<byte[]> values;

		private Fixture(QpackDynamicTable table, List<HttpHeader> names, List<byte[]> values) {
			this.table = table;
			this.names = names;
			this.values = values;
		}

		/** A table holding exactly {@code entries} distinct entries, with a capacity too wide to evict any. */
		static Fixture of(int entries) {
			List<HttpHeader> names = new ArrayList<>(entries);
			List<byte[]> values = new ArrayList<>(entries);
			int capacity = 0;
			for (int i = 0; i < entries; i++) {
				HttpHeader name = HttpHeaders.of("x-lookup-" + i);
				byte[] value = encodeAscii("value-" + i);
				names.add(name);
				values.add(value);
				capacity += QpackDynamicTable.entrySize(name, value);
			}

			QpackDynamicTable table = QpackDynamicTable.forEncoder(capacity, 0);
			for (int i = 0; i < entries; i++) {
				long index = table.insert(names.get(i), values.get(i));
				if (index != i) throw new AssertionError("the fixture must not evict: entry " + i + " went in at " + index);
			}
			return new Fixture(table, names, values);
		}

		long findNameLookups(int lookups) {
			long checksum = 0;
			int size = names.size();
			for (int i = 0; i < lookups; i++) {
				checksum += table.findName(names.get(i % size));
			}
			return checksum;
		}

		long findNameAndValueLookups(int lookups) {
			long checksum = 0;
			int size = names.size();
			for (int i = 0; i < lookups; i++) {
				int entry = i % size;
				checksum += table.findNameAndValue(names.get(entry), values.get(entry));
			}
			return checksum;
		}

		/**
		 * What {@code findName} would cost without the index: newest-first over the same entries, which
		 * is the order a FIFO scan would have to take to answer "the newest entry with this name".
		 */
		long linearFindNameLookups(int lookups) {
			long checksum = 0;
			int size = names.size();
			for (int i = 0; i < lookups; i++) {
				HttpHeader needle = names.get(i % size);
				long found = -1;
				for (int entry = size - 1; entry >= 0; entry--) {
					if (names.get(entry).equals(needle)) {
						found = entry;
						break;
					}
				}
				checksum += found;
			}
			return checksum;
		}

		/** As {@link #linearFindNameLookups}, for the name-and-value lookup. */
		long linearFindNameAndValueLookups(int lookups) {
			long checksum = 0;
			int size = names.size();
			for (int i = 0; i < lookups; i++) {
				int needleEntry = i % size;
				HttpHeader needleName = names.get(needleEntry);
				byte[] needleValue = values.get(needleEntry);
				long found = -1;
				for (int entry = size - 1; entry >= 0; entry--) {
					if (names.get(entry).equals(needleName) && Arrays.equals(values.get(entry), needleValue)) {
						found = entry;
						break;
					}
				}
				checksum += found;
			}
			return checksum;
		}
	}

	private static Object read(QpackDynamicTable table, String fieldName) throws ReflectiveOperationException {
		Field field = QpackDynamicTable.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(table);
	}

	/** The lines between a method's signature and the line that closes it at the class's indent. */
	private static List<String> methodBody(List<String> lines, String signature) {
		for (int i = 0; i < lines.size(); i++) {
			if (!lines.get(i).contains(signature)) continue;
			List<String> body = new ArrayList<>();
			for (int j = i + 1; j < lines.size(); j++) {
				String line = lines.get(j);
				if (line.equals("\t}")) return body;
				body.add(line);
			}
			throw new AssertionError("no closing brace found for `" + signature + '`');
		}
		throw new AssertionError("no method matching `" + signature + "` in the source");
	}

	// endregion
}
