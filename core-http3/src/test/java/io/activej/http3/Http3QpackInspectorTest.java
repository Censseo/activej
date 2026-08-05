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

package io.activej.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.Http3Connection.QpackTable;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T037: what the two {@code Inspector}s report about the QPACK dynamic table — insertions, evictions
 * and the two numbers a dynamic-table hit rate is computed from — over a real exchange between a real
 * {@link Http3Server} and a real {@link Http3Client}.
 * <p>
 * The counters are asserted from both ends at once, because each endpoint holds <b>two</b> tables and
 * conflating them is the classic QPACK bug: what the client's encoder inserts is what the server's
 * decoder inserts, one wire instruction later, and the reports have to say so.
 * <p>
 * Both endpoints advertise the configured {@code SETTINGS_QPACK_BLOCKED_STREAMS} (16 by default) from
 * US2 on, so an encoder may reference an entry the Known Received Count does not cover yet — the
 * insert-and-reference representation of FR-021, which blocks the stream until the peer's decoder has
 * taken the insertion. That is why the <i>first</i> section already reports dynamic references here:
 * until US2 it could not, since {@code Http3Connection} advertised 0 while it had nowhere to hold a
 * blocked section, and FR-020 degenerates at 0 to "reference only what the Known Received Count covers".
 */
public final class Http3QpackInspectorTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final MemSize CAPACITY = MemSize.kilobytes(4);

	/** One entry of {@link #REQUEST_HEADER} is ~90 bytes, so a second one never fits beside the first. */
	private static final MemSize ONE_ENTRY_CAPACITY = MemSize.bytes(128);

	private static final HttpHeader REQUEST_HEADER = HttpHeaders.of("x-activej-request-mark");
	private static final HttpHeader RESPONSE_HEADER = HttpHeaders.of("x-activej-response-mark");

	private static final String REQUEST_VALUE = "a-repeated-request-field-value-worth-indexing";
	private static final String RESPONSE_VALUE = "a-repeated-response-field-value-worth-indexing";

	private final List<TableEvent> tableEvents = new ArrayList<>();
	private final List<SectionEvent> sectionEvents = new ArrayList<>();

	private ManualEventloop loop;
	private @Nullable Http3ClientFixture fixture;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- insertions

	@Test
	public void oneExchangeReportsAnInsertionIntoAllFourTables() {
		start(CAPACITY);
		warmUp();
		exchange("/first", REQUEST_VALUE);

		assertTrue("the client's encoder inserted the request field: " + tableEvents,
			inserted("client", QpackTable.ENCODER));
		assertTrue("the server's decoder took that insertion off the encoder stream: " + tableEvents,
			inserted("server", QpackTable.DECODER));
		assertTrue("the server's encoder inserted the response field: " + tableEvents,
			inserted("server", QpackTable.ENCODER));
		assertTrue("the client's decoder took that one: " + tableEvents,
			inserted("client", QpackTable.DECODER));

		for (TableEvent event : tableEvents) {
			assertTrue("an insertion of nothing is not an event: " + event, event.count() > 0);
			assertTrue("a table that holds an entry holds bytes: " + event, event.tableBytes() > 0);
		}
	}

	@Test
	public void whatOneSideInsertsIsWhatTheOtherSideInserts() {
		start(CAPACITY);
		warmUp();
		exchange("/first", REQUEST_VALUE);
		exchange("/second", "a-second-request-field-value-worth-indexing");

		assertEquals("every insertion of the client's encoder reached the server's decoder",
			totalCount("client", QpackTable.ENCODER, "insertions"),
			totalCount("server", QpackTable.DECODER, "insertions"));
		assertEquals(
			totalCount("server", QpackTable.ENCODER, "insertions"),
			totalCount("client", QpackTable.DECODER, "insertions"));
	}

	// ---------------------------------------------------------------- evictions

	@Test
	public void aTableThatHoldsOneEntryReportsAnEvictionPerInsertionAfterTheFirst() {
		start(ONE_ENTRY_CAPACITY);
		warmUp();
		exchange("/first", REQUEST_VALUE + "-1");
		exchange("/second", REQUEST_VALUE + "-2");
		exchange("/third", REQUEST_VALUE + "-3");

		assertTrue("the client's encoder had to evict to insert: " + tableEvents,
			evicted("client", QpackTable.ENCODER));
		assertTrue("and so did the server's decoder, on the same instructions: " + tableEvents,
			evicted("server", QpackTable.DECODER));
		assertEquals("an eviction on one side is an eviction on the other",
			totalCount("client", QpackTable.ENCODER, "evictions"),
			totalCount("server", QpackTable.DECODER, "evictions"));
	}

	// ---------------------------------------------------------------- the hit rate

	@Test
	public void theSecondSectionCarryingTheSameFieldIsReportedAsADynamicHit() {
		start(CAPACITY);
		warmUp();
		exchange("/first", REQUEST_VALUE);
		exchange("/second", REQUEST_VALUE);

		List<SectionEvent> encoded = sectionsOf("client");
		assertEquals("one field section per request", 2, encoded.size());
		assertTrue("every section carries field lines: " + encoded, encoded.get(0).fieldLines() > 0);
		assertTrue("the repeated field came out of the dynamic table: " + encoded,
			encoded.get(1).dynamicReferences() > 0);
		for (SectionEvent section : encoded) {
			assertTrue("a reference is never counted twice per field line: " + section,
				section.dynamicReferences() <= section.fieldLines());
		}
	}

	@Test
	public void everySectionIsReportedAgainstTheStreamThatCarriedIt() {
		start(CAPACITY);
		warmUp();
		exchange("/first", REQUEST_VALUE);

		List<SectionEvent> encoded = sectionsOf("client");
		assertEquals(1, encoded.size());
		assertEquals(
			"RFC 9000 §2.1 spaces client-initiated bidirectional ids by 4, and stream 0 carried the warm-up",
			4, encoded.get(0).streamId());
	}

	// ---------------------------------------------------------------- off by default

	@Test
	public void aDefaultConnectionReportsNoQpackEventAtAll() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> HttpResponse.ok200()
				.withHeader(RESPONSE_HEADER, RESPONSE_VALUE + request.getPath())
				.toPromise())
			.withServerInspector(new CountingServerInspector())
			.withClientInspector(new CountingClientInspector())
			.start();

		exchange("/first", REQUEST_VALUE);
		exchange("/second", REQUEST_VALUE);

		assertTrue("SC-011: a capacity of 0 has no dynamic table to report on: " + tableEvents,
			tableEvents.isEmpty());
		assertTrue("and encodes no section through one: " + sectionEvents, sectionEvents.isEmpty());
	}

	// ---------------------------------------------------------------- SI-6

	@Test
	public void noQpackCounterCanCarryAnythingButANumber() {
		assertParametersAreNumbers(Http3Server.Inspector.class, Http3Server.class);
		assertParametersAreNumbers(Http3Client.Inspector.class, Http3Client.class);
	}

	/**
	 * Every QPACK event a connection reports reaches both public surfaces. The internal seam is where a
	 * counter is <i>raised</i>, so an event added there and forwarded to only one of the two — or to
	 * neither — is the failure this catches, and it is not visible from either side alone.
	 */
	@Test
	public void everyQpackEventAConnectionReportsIsOnBothInspectors() {
		Set<String> reported = qpackMethodNames(Http3EventListener.class);
		assertEquals("insertions, evictions, the hit rate, and blocked-stream entry, exit and refusal",
			6, reported.size());
		assertEquals(reported, qpackMethodNames(Http3Server.Inspector.class));
		assertEquals(reported, qpackMethodNames(Http3Client.Inspector.class));
	}

	private static Set<String> qpackMethodNames(Class<?> type) {
		Set<String> names = new TreeSet<>();
		for (Method method : type.getDeclaredMethods()) {
			if (method.getName().startsWith("onQpack")) names.add(method.getName());
		}
		return names;
	}

	/**
	 * Structural rather than sampled: {@code Http3SecretsStayOutOfLogsTest} searches captured arguments
	 * for known secrets, which can only find what a test thought to plant. A parameter list that admits
	 * no {@code String} and no {@code byte[]} cannot carry a field name or a field value at all.
	 * <p>
	 * An {@code enum} passes for the same reason a primitive does: its constants are declared in this
	 * module, so it is a closed set of names this codebase chose rather than anything a peer sent.
	 */
	private static void assertParametersAreNumbers(Class<?> inspector, Class<?> component) {
		int found = 0;
		for (Method method : inspector.getDeclaredMethods()) {
			if (!method.getName().startsWith("onQpack")) continue;
			found++;
			Class<?>[] parameters = method.getParameterTypes();
			assertEquals(component, parameters[0]);
			for (int i = 1; i < parameters.length; i++) {
				Class<?> parameter = parameters[i];
				assertTrue(method + " carries " + parameter.getSimpleName(),
					parameter.isPrimitive() || parameter.isEnum());
			}
			assertTrue(method + " must not break an existing implementation", method.isDefault());
		}
		assertEquals("insertions, evictions, the hit rate, and blocked-stream entry, exit and refusal",
			6, found);
	}

	// ---------------------------------------------------------------- the fixture

	private void start(MemSize capacity) {
		Http3Settings settings = Http3Settings.builder().withQpackMaxTableCapacity(capacity).build();
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> HttpResponse.ok200()
				.withHeader(RESPONSE_HEADER, RESPONSE_VALUE + request.getPath())
				.toPromise())
			.withServerSettings(settings)
			.withClientSettings(settings)
			.withServerInspector(new CountingServerInspector())
			.withClientInspector(new CountingClientInspector())
			.start();
	}

	/**
	 * One exchange, then a clean slate. The client's own encoder cannot exist before the peer's SETTINGS
	 * have landed — RFC 9204 §3.2.3 makes the peer's advertised maximum the ceiling on it (FR-019) — and
	 * it is the <i>first request</i> that dials the connection those SETTINGS arrive on. So the first
	 * request a client ever sends is encoded statically, on every connection, by construction; measuring
	 * from the second is measuring the steady state rather than the handshake.
	 */
	private void warmUp() {
		exchange("/warm-up", "a-warm-up-field-value-nothing-else-repeats");
		tableEvents.clear();
		sectionEvents.clear();
	}

	private void exchange(String path, String requestValue) {
		HttpResponse response = fixture.await(fixture.client().request(
			HttpRequest.get(url(HOST, path))
				.withHeader(REQUEST_HEADER, requestValue)
				.build()));
		assertEquals(200, response.getCode());
		ByteBuf body = fixture.await(response.loadBody());
		body.recycle();
		// The acknowledgment and the Insert Count Increment travel behind the response, and the next
		// section is planned against what they advance — so they have to land before the next exchange.
		fixture.wire().advance(10);
	}

	private boolean inserted(String endpoint, QpackTable table) {
		return totalCount(endpoint, table, "insertions") > 0;
	}

	private boolean evicted(String endpoint, QpackTable table) {
		return totalCount(endpoint, table, "evictions") > 0;
	}

	private int totalCount(String endpoint, QpackTable table, String kind) {
		int total = 0;
		for (TableEvent event : tableEvents) {
			if (event.endpoint().equals(endpoint) && event.table() == table && event.kind().equals(kind)) {
				total += event.count();
			}
		}
		return total;
	}

	private List<SectionEvent> sectionsOf(String endpoint) {
		List<SectionEvent> sections = new ArrayList<>();
		for (SectionEvent event : sectionEvents) {
			if (event.endpoint().equals(endpoint)) sections.add(event);
		}
		return sections;
	}

	private record TableEvent(String endpoint, String kind, QpackTable table, int count, int tableBytes) {}

	private record SectionEvent(String endpoint, long streamId, int fieldLines, int dynamicReferences) {}

	private final class CountingServerInspector implements Http3Server.Inspector {
		@Override
		public <T extends Http3Server.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Server server, long streamId, HttpMethod method) {}

		@Override
		public void onRequestCompleted(
			Http3Server server, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {}

		@Override
		public void onStreamReset(Http3Server server, long streamId, long errorCode) {}

		@Override
		public void onConnectionError(Http3Server server, long errorCode) {}

		@Override
		public void onFrameDiscarded(Http3Server server, long frameType, long declaredLength) {}

		@Override
		public void onGoAway(Http3Server server, GoAwayDirection direction, long id) {}

		@Override
		public void onQpackInsertions(Http3Server server, QpackTable table, int insertions, int tableBytes) {
			tableEvents.add(new TableEvent("server", "insertions", table, insertions, tableBytes));
		}

		@Override
		public void onQpackEvictions(Http3Server server, QpackTable table, int evictions, int tableBytes) {
			tableEvents.add(new TableEvent("server", "evictions", table, evictions, tableBytes));
		}

		@Override
		public void onQpackFieldSectionEncoded(
			Http3Server server, long streamId, int fieldLines, int dynamicReferences
		) {
			sectionEvents.add(new SectionEvent("server", streamId, fieldLines, dynamicReferences));
		}
	}

	private final class CountingClientInspector implements Http3Client.Inspector {
		@Override
		public <T extends Http3Client.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Client client, long streamId, HttpMethod method) {}

		@Override
		public void onRequestCompleted(
			Http3Client client, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {}

		@Override
		public void onStreamReset(Http3Client client, long streamId, long errorCode) {}

		@Override
		public void onConnectionError(Http3Client client, long errorCode) {}

		@Override
		public void onFrameDiscarded(Http3Client client, long frameType, long declaredLength) {}

		@Override
		public void onGoAway(Http3Client client, GoAwayDirection direction, long id) {}

		@Override
		public void onRequestQueued(Http3Client client, int queueDepth) {}

		@Override
		public void onRequestDequeued(Http3Client client, int queueDepth) {}

		@Override
		public void onQpackInsertions(Http3Client client, QpackTable table, int insertions, int tableBytes) {
			tableEvents.add(new TableEvent("client", "insertions", table, insertions, tableBytes));
		}

		@Override
		public void onQpackEvictions(Http3Client client, QpackTable table, int evictions, int tableBytes) {
			tableEvents.add(new TableEvent("client", "evictions", table, evictions, tableBytes));
		}

		@Override
		public void onQpackFieldSectionEncoded(
			Http3Client client, long streamId, int fieldLines, int dynamicReferences
		) {
			sectionEvents.add(new SectionEvent("client", streamId, fieldLines, dynamicReferences));
		}
	}
}
