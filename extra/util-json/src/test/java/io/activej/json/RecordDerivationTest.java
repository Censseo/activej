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

package io.activej.json;

import io.activej.common.exception.MalformedDataException;
import io.activej.json.annotations.JsonNullable;
import io.activej.json.annotations.JsonSubclasses;
import io.activej.json.otherpackage.OtherPackageRecords;
import io.activej.types.TypeT;
import org.junit.Test;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Derivation as a <b>consumer</b> sees it: through the shipped
 * {@link JsonCodecFactory#defaultInstance()}, with <b>no {@code Builder.with(...)} call anywhere</b>.
 * <p>
 * That distinction is the whole point of this class and is what separates it from
 * {@code RecordJsonCodecTest}, which drives {@code RecordJsonCodec} directly through a test-local
 * registration and a test-local memo. Everything here would still pass against that harness; none of
 * it would prove the feature is <i>reachable</i>. Prefer adding a case here when the question is "does
 * a user get this", and there when the question is "does the derivation compute this".
 * <p>
 * Bare JUnit 4, no {@code @ClassRule}: {@code activej-test} is not on this module's classpath and
 * nothing here touches a {@code ByteBuf}, a {@code Promise} or a reactor.
 */
public class RecordDerivationTest {

	// ---------------------------------------------------------------- US1-1 / US1-2, the headline model

	record Line(String sku, int qty) {}

	record Customer(long id, String name) {}

	record Order(long id, Customer customer, List<Line> lines) {}

	/**
	 * The literal below is not a convenience: it is the string the phase-0 spike produced
	 * (research §0 q1), reproduced byte for byte. A round-trip assertion alone would pass for a codec
	 * that renamed every key or reordered every member, and the JSON of a derived record is a wire
	 * commitment consumers persist — so the bytes are the assertion and the round trip is the
	 * corroboration.
	 */
	@Test
	public void nestedRecordsRoundTripWithZeroRegistrations() throws MalformedDataException {
		JsonCodec<Order> codec = JsonCodecFactory.defaultInstance().resolve(Order.class);

		Order order = new Order(7, new Customer(1, "ann"), List.of(new Line("x", 2), new Line("y", 3)));
		String json = "{\"id\":7,\"customer\":{\"id\":1,\"name\":\"ann\"},\"lines\":[{\"sku\":\"x\",\"qty\":2},{\"sku\":\"y\",\"qty\":3}]}";

		assertEquals(json, JsonUtils.toJson(codec, order));
		assertEquals(order, JsonUtils.fromJson(codec, json));
	}

	// ---------------------------------------------------------------- SC-001, ten records

	record Money(String currency, long amount) {}

	record Address(String street, String city) {}

	record Contact(String email, @JsonNullable String phone) {}

	record Buyer(long id, String name, Address address, Contact contact) {}

	record Sku(String code) {}

	record Item(Sku sku, int qty, Money price) {}

	record Shipment(String carrier, LocalDate shippedOn) {}

	record Payment(String method, Money total) {}

	record Invoice(long number, Payment payment, List<Item> items) {}

	record PurchaseOrder(long id, Buyer buyer, Invoice invoice, List<Shipment> shipments) {}

	/**
	 * SC-001 says <b>ten</b> record types, so the list is asserted rather than eyeballed: deleting a
	 * record from the model to simplify a future edit fails here instead of quietly weakening the
	 * success criterion. Three nested records — the case above — do not demonstrate it.
	 * <p>
	 * {@code LocalDate} is in the model deliberately: a derived record must compose with the codecs the
	 * factory already had, not only with other derived records.
	 */
	private static final List<Class<?>> TEN_RECORD_MODEL = List.of(
		Money.class, Address.class, Contact.class, Buyer.class, Sku.class,
		Item.class, Shipment.class, Payment.class, Invoice.class, PurchaseOrder.class);

	@Test
	public void tenRecordModelResolvesWithZeroRegistrations() throws MalformedDataException {
		assertEquals(10, TEN_RECORD_MODEL.size());

		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();
		JsonCodec<PurchaseOrder> codec = factory.resolve(PurchaseOrder.class);

		PurchaseOrder purchaseOrder = new PurchaseOrder(
			42,
			new Buyer(1, "ann",
				new Address("1 Main St", "Springfield"),
				new Contact("ann@example.com", null)),
			new Invoice(1001,
				new Payment("card", new Money("EUR", 4500)),
				List.of(
					new Item(new Sku("x"), 2, new Money("EUR", 1500)),
					new Item(new Sku("y"), 1, new Money("EUR", 1500)))),
			List.of(new Shipment("dhl", LocalDate.of(2026, 8, 12))));

		String json =
			"{\"id\":42," +
			"\"buyer\":{\"id\":1,\"name\":\"ann\"," +
			"\"address\":{\"street\":\"1 Main St\",\"city\":\"Springfield\"}," +
			"\"contact\":{\"email\":\"ann@example.com\",\"phone\":null}}," +
			"\"invoice\":{\"number\":1001," +
			"\"payment\":{\"method\":\"card\",\"total\":{\"currency\":\"EUR\",\"amount\":4500}}," +
			"\"items\":[" +
			"{\"sku\":{\"code\":\"x\"},\"qty\":2,\"price\":{\"currency\":\"EUR\",\"amount\":1500}}," +
			"{\"sku\":{\"code\":\"y\"},\"qty\":1,\"price\":{\"currency\":\"EUR\",\"amount\":1500}}]}," +
			"\"shipments\":[{\"carrier\":\"dhl\",\"shippedOn\":\"2026-08-12\"}]}";

		assertEquals(json, JsonUtils.toJson(codec, purchaseOrder));
		assertEquals(purchaseOrder, JsonUtils.fromJson(codec, json));

		// every one of the ten is also resolvable in its own right, not merely reachable as a member
		for (Class<?> recordClass : TEN_RECORD_MODEL) {
			assertNotNull(recordClass.getName(), factory.resolve((Type) recordClass));
		}
	}

	// ---------------------------------------------------------------- FR-003 / FR-004 / FR-005

	/** Component names chosen to be hostile to any naming strategy: camel, snake, upper, digit. */
	record Naming(String userName, String user_id, String URL, int v2) {}

	@Test
	public void memberOrderIsCanonicalAndKeysAreVerbatim() {
		JsonCodec<Naming> codec = JsonCodecFactory.defaultInstance().resolve(Naming.class);

		// declaration order, not alphabetical; keys unchanged, not camelCased, snake_cased or lowered
		assertEquals(
			"{\"userName\":\"a\",\"user_id\":\"b\",\"URL\":\"c\",\"v2\":2}",
			JsonUtils.toJson(codec, new Naming("a", "b", "c", 2)));
	}

	@Test
	public void decodingAcceptsMembersInAnyOrder() throws MalformedDataException {
		JsonCodec<Naming> codec = JsonCodecFactory.defaultInstance().resolve(Naming.class);

		assertEquals(
			new Naming("a", "b", "c", 2),
			JsonUtils.fromJson(codec, "{\"v2\":2,\"URL\":\"c\",\"user_id\":\"b\",\"userName\":\"a\"}"));
	}

	// ---------------------------------------------------------------- US1-3 / US1-4, FR-006

	sealed interface Shape permits Circle, Square {}

	record Circle(int radius) implements Shape {}

	record Square(int side) implements Shape {}

	record Profile(@JsonNullable String nickname, @JsonSubclasses({Circle.class, Square.class}) Shape favourite) {}

	/**
	 * Neither annotation is read by anything in the derivation: resolving each component on its
	 * <b>annotated</b> type hands it to the wrapper {@code JsonCodecFactory.Builder.with} already puts
	 * around every registered mapping. The assertion is that the two mechanisms meet, through the
	 * shipped factory.
	 */
	@Test
	public void componentAnnotationsApplyInsideADerivedRecord() throws MalformedDataException {
		JsonCodec<Profile> codec = JsonCodecFactory.defaultInstance().resolve(Profile.class);

		String json = "{\"nickname\":null,\"favourite\":{\"Square\":{\"side\":4}}}";
		assertEquals(json, JsonUtils.toJson(codec, new Profile(null, new Square(4))));
		assertEquals(new Profile(null, new Square(4)), JsonUtils.fromJson(codec, json));

		String named = "{\"nickname\":\"ann\",\"favourite\":{\"Circle\":{\"radius\":3}}}";
		assertEquals(named, JsonUtils.toJson(codec, new Profile("ann", new Circle(3))));
		assertEquals(new Profile("ann", new Circle(3)), JsonUtils.fromJson(codec, named));
	}

	// ---------------------------------------------------------------- US1-5, FR-007

	record Pair<A, B>(A first, B second) {}

	@Test
	public void genericRecordSubstitutesEachComponent() throws MalformedDataException {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		JsonCodec<Pair<String, Integer>> codec = factory.resolve(new TypeT<Pair<String, Integer>>() {});
		assertEquals("{\"first\":\"x\",\"second\":2}", JsonUtils.toJson(codec, new Pair<>("x", 2)));
		assertEquals(new Pair<>("x", 2), JsonUtils.fromJson(codec, "{\"first\":\"x\",\"second\":2}"));

		// the swapped instantiation is a different codec, not the same one reused: each component is
		// substituted independently, so the arguments cannot be silently transposed
		JsonCodec<Pair<Integer, String>> swapped = factory.resolve(new TypeT<Pair<Integer, String>>() {});
		assertEquals("{\"first\":2,\"second\":\"x\"}", JsonUtils.toJson(swapped, new Pair<>(2, "x")));
		assertEquals(new Pair<>(2, "x"), JsonUtils.fromJson(swapped, "{\"first\":2,\"second\":\"x\"}"));
	}

	// ---------------------------------------------------------------- US1-6, SC-006

	record Node(String v, List<Node> kids) {}

	record Ping(Pong pong) {}

	record Pong(List<Ping> pings) {}

	/**
	 * Measured to be a {@link StackOverflowError} at <b>derivation</b> time without the memo
	 * (research §0 q5) — which is why the memo is a correctness requirement rather than a cache. This
	 * is the version that matters to a consumer: no test-local cache is constructed anywhere below,
	 * because the factory now owns one.
	 */
	@Test
	public void selfReferencingRecordRoundTrips() throws MalformedDataException {
		JsonCodec<Node> codec = JsonCodecFactory.defaultInstance().resolve(Node.class);

		Node tree = new Node("a", List.of(new Node("b", List.of(new Node("c", List.of())))));
		String json = "{\"v\":\"a\",\"kids\":[{\"v\":\"b\",\"kids\":[{\"v\":\"c\",\"kids\":[]}]}]}";

		assertEquals(json, JsonUtils.toJson(codec, tree));
		assertEquals(tree, JsonUtils.fromJson(codec, json));
	}

	/** Both directions: a memo that only breaks self-reference passes one of these and fails the other. */
	@Test
	public void mutuallyRecursiveRecordsRoundTrip() throws MalformedDataException {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		Ping ping = new Ping(new Pong(List.of(new Ping(new Pong(List.of())))));
		String pingJson = "{\"pong\":{\"pings\":[{\"pong\":{\"pings\":[]}}]}}";

		JsonCodec<Ping> pingCodec = factory.resolve(Ping.class);
		assertEquals(pingJson, JsonUtils.toJson(pingCodec, ping));
		assertEquals(ping, JsonUtils.fromJson(pingCodec, pingJson));

		JsonCodec<Pong> pongCodec = factory.resolve(Pong.class);
		String pongJson = "{\"pings\":[{\"pong\":{\"pings\":[]}}]}";
		assertEquals(pongJson, JsonUtils.toJson(pongCodec, ping.pong()));
		assertEquals(ping.pong(), JsonUtils.fromJson(pongCodec, pongJson));
	}

	// ---------------------------------------------------------------- US1-7

	/**
	 * {@code record Secret(int n, String label)} is package-private in {@code io.activej.json.otherpackage}
	 * and cannot be named from here — it is reached only through {@code Class<?>} and {@code Object}, which
	 * is why this test asserts on JSON text and on {@code equals} rather than on a typed value.
	 * <p>
	 * Exercises <b>package</b> access only. The repository has no {@code module-info.java}, so the
	 * JPMS non-exported case is not reproduced anywhere and is not claimed by this test.
	 */
	@Test
	public void nonPublicRecordInAnotherPackageBinds() throws MalformedDataException {
		JsonCodec<Object> codec = JsonCodecFactory.defaultInstance().resolve((Type) OtherPackageRecords.secretClass());

		Object secret = OtherPackageRecords.secret(7, "x");
		String json = "{\"n\":7,\"label\":\"x\"}";

		assertEquals(json, JsonUtils.toJson(codec, secret));
		assertEquals(secret, JsonUtils.fromJson(codec, json));
	}

	// ---------------------------------------------------------------- edge case: no components

	record Empty() {}

	@Test
	public void zeroComponentRecordEncodesToEmptyObject() throws MalformedDataException {
		JsonCodec<Empty> codec = JsonCodecFactory.defaultInstance().resolve(Empty.class);

		assertEquals("{}", JsonUtils.toJson(codec, new Empty()));
		assertEquals(new Empty(), JsonUtils.fromJson(codec, "{}"));
	}

	// ---------------------------------------------------------------- SC-007 and FR-010's regime

	record Counted(String a, int b) {}

	/**
	 * FR-010 declares <i>when</i> derivation happens — once per type, at resolve, never per encode or
	 * per decode. A counter is the only way to observe that; inspecting the codec cannot distinguish a
	 * memo hit from a second derivation that produced an equal result.
	 * <p>
	 * The counter sits on the <b>component</b> mapping, not on the {@code Record} mapping: the record
	 * mapping is entered on every resolve, memo hit included, so counting there would count scans and
	 * pin nothing. Entering the {@code String} mapping means the record's body was walked.
	 * <p>
	 * This is the one test here that rebuilds — a counter cannot be installed otherwise — and the
	 * rebuild is itself load-bearing: {@code rebuild()} must hand the new factory an <b>empty</b> memo
	 * (FR-016), or the count would depend on whichever earlier test resolved {@code Counted} first.
	 */
	@Test
	public void derivationHappensOncePerTypeAndNeverPerValue() throws MalformedDataException {
		AtomicInteger componentScans = new AtomicInteger();
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance().rebuild()
			.with(String.class, ctx -> {
				componentScans.incrementAndGet();
				return JsonCodecs.ofString();
			})
			.build();

		JsonCodec<Counted> first = factory.resolve(Counted.class);
		JsonCodec<Counted> second = factory.resolve(Counted.class);

		assertSame(first, second);
		assertEquals(1, componentScans.get());

		for (int i = 0; i < 5; i++) {
			assertEquals(new Counted("x", i), JsonUtils.fromJson(first, JsonUtils.toJson(first, new Counted("x", i))));
		}
		assertEquals(1, componentScans.get());
	}

	// ---------------------------------------------------------------- SC-011, FR-042

	record Box<T>(T value) {}

	/**
	 * The two resolutions share a {@code Type} and differ only in an annotation on a type argument, so
	 * a memo keyed on {@code Type} alone would hand the second caller the first's codec — silently, and
	 * with the wrong null handling. Both go through the <b>same</b> factory instance on purpose;
	 * against two factories the assertion would hold even with a broken key.
	 */
	@Test
	public void annotatedTypeArgumentsGetDistinctCodecs() throws MalformedDataException {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		JsonCodec<Box<String>> nullable = factory.resolve(new TypeT<Box<@JsonNullable String>>() {});
		assertEquals("{\"value\":null}", JsonUtils.toJson(nullable, new Box<>(null)));
		assertEquals(new Box<String>(null), JsonUtils.fromJson(nullable, "{\"value\":null}"));

		JsonCodec<Box<String>> plain = factory.resolve(new TypeT<Box<String>>() {});
		assertNotSame(nullable, plain);
		assertThrows(NullPointerException.class, () -> JsonUtils.toJson(plain, new Box<>(null)));
	}

	// ---------------------------------------------------------------- T070, SC-012 / FR-044 / FR-037

	record Drawing(@JsonSubclasses({Circle.class, Square.class}) Shape shape) {}

	/**
	 * A whole {@code sealed} hierarchy whose permitted subclasses are records, round-tripped
	 * polymorphically with <b>no codec written for any subclass</b> — the consumer writes the
	 * {@code @JsonSubclasses} annotation and nothing else. Distinct from
	 * {@link #componentAnnotationsApplyInsideADerivedRecord}, which covers one annotated component;
	 * here both arms of the hierarchy are exercised, standing alone and inside a {@code List}.
	 */
	@Test
	public void sealedInterfaceOfRecordsRoundTripsPolymorphically() throws MalformedDataException {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		JsonCodec<Drawing> codec = factory.resolve(Drawing.class);
		String circle = "{\"shape\":{\"Circle\":{\"radius\":3}}}";
		assertEquals(circle, JsonUtils.toJson(codec, new Drawing(new Circle(3))));
		assertEquals(new Drawing(new Circle(3)), JsonUtils.fromJson(codec, circle));

		String square = "{\"shape\":{\"Square\":{\"side\":4}}}";
		assertEquals(square, JsonUtils.toJson(codec, new Drawing(new Square(4))));
		assertEquals(new Drawing(new Square(4)), JsonUtils.fromJson(codec, square));

		JsonCodec<List<Shape>> listCodec =
			factory.resolve(new TypeT<List<@JsonSubclasses({Circle.class, Square.class}) Shape>>() {});
		List<Shape> shapes = List.of(new Circle(3), new Square(4));
		String listJson = "[{\"Circle\":{\"radius\":3}},{\"Square\":{\"side\":4}}]";
		assertEquals(listJson, JsonUtils.toJson(listCodec, shapes));
		assertEquals(shapes, JsonUtils.fromJson(listCodec, listJson));
	}

	sealed interface Untagged permits UntaggedCircle, UntaggedSquare {}

	record UntaggedCircle(int radius) implements Untagged {}

	record UntaggedSquare(int side) implements Untagged {}

	/**
	 * FR-037: inference from the {@code permits} clause is <b>not</b> implemented, and this pins that
	 * as behaviour rather than as a note. Nothing in {@code src/main} calls
	 * {@code Class.getPermittedSubclasses()}; a sealed interface with no {@code @JsonSubclasses} is
	 * therefore an ordinary unregistered type and still reaches the untouched {@code Object.class}
	 * fallback.
	 * <p>
	 * The second half is what makes the first half meaningful: the permitted <i>records</i> do derive,
	 * so the failure above is the missing annotation and not a missing capability.
	 */
	@Test
	public void permitsClauseIsNotInferred() throws MalformedDataException {
		JsonCodecFactory factory = JsonCodecFactory.defaultInstance();

		assertThrows(UnsupportedOperationException.class, () -> factory.resolve(Untagged.class));

		JsonCodec<UntaggedCircle> codec = factory.resolve(UntaggedCircle.class);
		assertEquals("{\"radius\":3}", JsonUtils.toJson(codec, new UntaggedCircle(3)));
		assertEquals(new UntaggedCircle(3), JsonUtils.fromJson(codec, "{\"radius\":3}"));
	}
}
