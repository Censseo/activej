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

package io.activej.jsonrpc.schema;

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Any;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Typed;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Pins the emitted bytes of the OpenRPC document model against
 * {@code specs/018-jsonrpc-schema-discovery/contracts/openrpc-mapping.md} §1 — the document skeleton
 * and rules M1…M10.
 *
 * <p>Every assertion here is on the <b>serialised string</b>, not on the object graph, because the
 * rules this feature has to keep are rules about <i>bytes</i>: a member that is <b>absent</b> versus
 * one written as {@code null} (M3), a schema that is the bare JSON literal {@code true} versus an
 * empty object (M8), and a member order that is fixed rather than merely consistent (M10). A
 * structural assertion passes for all three of those mistakes; the frozen reference documents of
 * phase US3 (FR-051) would then be the first thing to notice, which is far too late.
 *
 * <p>Values are constructed <b>directly</b>, never through a generator: this file is the model's own
 * contract, and coupling it to {@code JsonRpcSchemaGenerator} would make one failure look like two.
 * The schema fragments likewise come from {@link JsonSchemaFragment}'s factories rather than from
 * {@link JsonSchemaMapping}, so a mapping-table change cannot break a document-shape assertion.
 *
 * <p>No {@code EventloopRule}, no {@code ByteBufRule}: this package is deliberately synchronous and
 * reactor-free, and a rule here would be the first import that made it otherwise.
 */
public final class OpenRpcDocumentTest {

	// ---------------------------------------------------------------------------------------------
	// Fixtures
	// ---------------------------------------------------------------------------------------------

	private static final OpenRpcInfo INFO = new OpenRpcInfo("Reference API", "1.0.0");

	/** A named parameter: {@code @JsonRpcParam("id") long id}. */
	private static final OpenRpcContentDescriptor ID_PARAM =
		new OpenRpcContentDescriptor("id", Typed.of("integer"));

	/** A result inside the subset — the {@code record R} row, with its members in canonical order. */
	private static final OpenRpcContentDescriptor USER_RESULT =
		new OpenRpcContentDescriptor("user", userSchema());

	private static final String USER_SCHEMA_JSON =
		"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}," +
		"\"name\":{\"type\":\"string\"}},\"required\":[\"id\",\"name\"]}";

	private static Typed userSchema() {
		Map<String, JsonSchemaFragment> properties = new LinkedHashMap<>();
		properties.put("id", Typed.of("integer"));
		properties.put("name", Typed.of("string"));
		return Typed.object(properties, List.of("id", "name"));
	}

	// ---------------------------------------------------------------------------------------------
	// (a) a full document: every member, top to bottom, in the contract's order
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aFullDocumentEmitsEveryMemberInTheFixedOrder() {
		OpenRpcMethod get = new OpenRpcMethod("user.get", List.of(ID_PARAM), USER_RESULT, null);

		assertEquals(
			"{\"openrpc\":\"1.4.0\"," +
			"\"info\":{\"title\":\"Reference API\",\"version\":\"1.0.0\"}," +
			"\"methods\":[" +
			"{\"name\":\"user.get\"," +
			"\"params\":[{\"name\":\"id\",\"required\":true,\"schema\":{\"type\":\"integer\"}}]," +
			"\"result\":{\"name\":\"user\",\"required\":true,\"schema\":" + USER_SCHEMA_JSON + "}}" +
			"]}",
			write(OpenRpcDocument.of(INFO, List.of(get))));
	}

	@Test
	public void theOpenRpcVersionIsPinnedAndIsTheFirstMember() {
		assertEquals("1.4.0", OpenRpcDocument.OPENRPC_VERSION);
		OpenRpcDocument document = OpenRpcDocument.of(INFO, List.of());
		assertEquals(OpenRpcDocument.OPENRPC_VERSION, document.openrpc());
		assertTrue(write(document).startsWith("{\"openrpc\":\"1.4.0\","));
	}

	@Test
	public void paramsAppearInDeclarationOrder() {
		OpenRpcContentDescriptor from = new OpenRpcContentDescriptor("from", Typed.of("string"));
		OpenRpcContentDescriptor to = new OpenRpcContentDescriptor("to", Typed.of("string"));
		OpenRpcMethod method = new OpenRpcMethod("mail.send", List.of(from, to),
			new OpenRpcContentDescriptor("sent", Typed.of("boolean")), null);

		assertEquals(
			"{\"name\":\"mail.send\",\"params\":[" +
			"{\"name\":\"from\",\"required\":true,\"schema\":{\"type\":\"string\"}}," +
			"{\"name\":\"to\",\"required\":true,\"schema\":{\"type\":\"string\"}}]," +
			"\"result\":{\"name\":\"sent\",\"required\":true,\"schema\":{\"type\":\"boolean\"}}}",
			JsonUtils.toJson(OpenRpcMethod.CODEC, method));
	}

	// ---------------------------------------------------------------------------------------------
	// (b) M3 — a notification's Method Object has NO result member, not a null one
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aNotificationCarriesNoResultMemberAtAll() {
		OpenRpcMethod touch = new OpenRpcMethod("user.touch", List.of(ID_PARAM), null, null);
		String json = JsonUtils.toJson(OpenRpcMethod.CODEC, touch);

		assertEquals(
			"{\"name\":\"user.touch\"," +
			"\"params\":[{\"name\":\"id\",\"required\":true,\"schema\":{\"type\":\"integer\"}}]}",
			json);

		// the same statement said twice, because "absent" and "null" are different documents to an
		// OpenRPC consumer: undefined result IS the notification marker
		assertFalse(json, json.contains("result"));
		assertFalse(json, json.contains("null"));
	}

	@Test
	public void aCallableMethodAndANotificationDifferOnlyByThatMember() {
		OpenRpcMethod callable = new OpenRpcMethod("user.get", List.of(ID_PARAM), USER_RESULT, null);
		OpenRpcMethod notification = new OpenRpcMethod("user.get", List.of(ID_PARAM), null, null);

		String callableJson = JsonUtils.toJson(OpenRpcMethod.CODEC, callable);
		String notificationJson = JsonUtils.toJson(OpenRpcMethod.CODEC, notification);

		assertEquals(notificationJson.substring(0, notificationJson.length() - 1),
			callableJson.substring(0, notificationJson.length() - 1));
	}

	// ---------------------------------------------------------------------------------------------
	// (c) M5 — paramStructure is "by-position" or the member is absent; never "either"
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aPositionalMethodCarriesParamStructureByPosition() {
		OpenRpcContentDescriptor param0 = new OpenRpcContentDescriptor("param0", Typed.of("integer"));
		OpenRpcContentDescriptor param1 = new OpenRpcContentDescriptor("param1", Typed.of("integer"));
		OpenRpcMethod add = new OpenRpcMethod("math.add", List.of(param0, param1),
			new OpenRpcContentDescriptor("sum", Typed.of("integer")), OpenRpcMethod.BY_POSITION);

		assertEquals(
			"{\"name\":\"math.add\",\"params\":[" +
			"{\"name\":\"param0\",\"required\":true,\"schema\":{\"type\":\"integer\"}}," +
			"{\"name\":\"param1\",\"required\":true,\"schema\":{\"type\":\"integer\"}}]," +
			"\"result\":{\"name\":\"sum\",\"required\":true,\"schema\":{\"type\":\"integer\"}}," +
			"\"paramStructure\":\"by-position\"}",
			JsonUtils.toJson(OpenRpcMethod.CODEC, add));
	}

	@Test
	public void aNamableMethodOmitsParamStructureEntirely() {
		OpenRpcMethod get = new OpenRpcMethod("user.get", List.of(ID_PARAM), USER_RESULT, null);
		String json = JsonUtils.toJson(OpenRpcMethod.CODEC, get);

		// the OpenRPC default "either" applies by absence — writing it out would claim a choice the
		// generator never made
		assertFalse(json, json.contains("paramStructure"));
		assertFalse(json, json.contains("either"));
	}

	@Test
	public void aPositionalNotificationCarriesParamStructureAndStillNoResult() {
		OpenRpcMethod touch = new OpenRpcMethod("user.touch",
			List.of(new OpenRpcContentDescriptor("param0", Typed.of("integer"))),
			null, OpenRpcMethod.BY_POSITION);

		assertEquals(
			"{\"name\":\"user.touch\"," +
			"\"params\":[{\"name\":\"param0\",\"required\":true,\"schema\":{\"type\":\"integer\"}}]," +
			"\"paramStructure\":\"by-position\"}",
			JsonUtils.toJson(OpenRpcMethod.CODEC, touch));
	}

	@Test
	public void anUnknownParamStructureIsRefusedAtConstruction() {
		assertThrows(IllegalArgumentException.class,
			() -> new OpenRpcMethod("user.get", List.of(), null, "by-guessing"));
	}

	// ---------------------------------------------------------------------------------------------
	// (d) M8 — a fragment outside the pinned subset is the bare JSON literal true, never {}
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aFallbackSchemaIsTheBareBooleanTrue() {
		OpenRpcContentDescriptor opaque = new OpenRpcContentDescriptor("token", Any.INSTANCE);
		String json = JsonUtils.toJson(OpenRpcContentDescriptor.CODEC, opaque);

		assertEquals("{\"name\":\"token\",\"required\":true,\"schema\":true}", json);
		assertFalse(json, json.contains("\"schema\":{}"));
	}

	@Test
	public void everyContentDescriptorCarriesRequiredTrue() {
		// M6: no optionality exists anywhere in this stack, so the member is unconditional — for a
		// result Content Descriptor as much as for a param
		assertTrue(JsonUtils.toJson(OpenRpcContentDescriptor.CODEC, ID_PARAM).contains("\"required\":true"));
		assertTrue(JsonUtils.toJson(OpenRpcContentDescriptor.CODEC, USER_RESULT).contains("\"required\":true"));
	}

	// ---------------------------------------------------------------------------------------------
	// (e) M2 — a service with zero methods is a valid document
	// ---------------------------------------------------------------------------------------------

	@Test
	public void aServiceWithNoMethodsIsAValidDocument() {
		assertEquals(
			"{\"openrpc\":\"1.4.0\"," +
			"\"info\":{\"title\":\"Reference API\",\"version\":\"1.0.0\"}," +
			"\"methods\":[]}",
			write(OpenRpcDocument.of(INFO, List.of())));
	}

	@Test
	public void aMethodWithNoParamsStillCarriesAnEmptyParamsArray() {
		// params is REQUIRED on an OpenRPC Method Object: rpc.discover-shaped methods have none, and
		// the member is still there
		assertEquals(
			"{\"name\":\"clock.now\",\"params\":[]," +
			"\"result\":{\"name\":\"now\",\"required\":true,\"schema\":{\"type\":\"integer\"}}}",
			JsonUtils.toJson(OpenRpcMethod.CODEC,
				new OpenRpcMethod("clock.now", List.of(),
					new OpenRpcContentDescriptor("now", Typed.of("integer")), null)));
	}

	// ---------------------------------------------------------------------------------------------
	// M9 — nothing this feature does not populate is ever emitted
	// ---------------------------------------------------------------------------------------------

	@Test
	public void noOptionalTopLevelMemberIsInvented() {
		String json = write(OpenRpcDocument.of(INFO,
			List.of(new OpenRpcMethod("user.get", List.of(ID_PARAM), USER_RESULT, null))));

		for (String absent : List.of(
			"servers", "externalDocs", "components", "contact", "license",
			"examples", "tags", "errors", "summary", "description", "deprecated")
		) {
			assertFalse(absent + " must not appear in " + json, json.contains(absent));
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The model itself
	// ---------------------------------------------------------------------------------------------

	@Test
	public void theDocumentRoundTripsThroughItsOwnCodec() throws MalformedDataException {
		OpenRpcDocument document = OpenRpcDocument.of(INFO, List.of(
			new OpenRpcMethod("user.get", List.of(ID_PARAM), USER_RESULT, null),
			new OpenRpcMethod("user.touch", List.of(ID_PARAM), null, null),
			new OpenRpcMethod("math.add",
				List.of(new OpenRpcContentDescriptor("param0", Typed.of("integer"))),
				new OpenRpcContentDescriptor("sum", Typed.of("integer")), OpenRpcMethod.BY_POSITION),
			new OpenRpcMethod("opaque.get", List.of(),
				new OpenRpcContentDescriptor("value", Any.INSTANCE), null)));

		String json = write(document);
		OpenRpcDocument read = JsonUtils.fromJson(OpenRpcDocument.CODEC, json);

		assertEquals(json, document, read);
		assertEquals(json, write(read));
	}

	@Test
	public void theModelIsImmutableOnceConstructed() {
		List<OpenRpcMethod> methods = new ArrayList<>();
		methods.add(new OpenRpcMethod("user.get", List.of(ID_PARAM), USER_RESULT, null));
		OpenRpcDocument document = OpenRpcDocument.of(INFO, methods);

		methods.clear();
		assertEquals(1, document.methods().size());
		assertThrows(UnsupportedOperationException.class, () -> document.methods().clear());
	}

	@Test
	public void requiredMembersAreRefusedAsNull() {
		assertThrows(NullPointerException.class, () -> new OpenRpcInfo(null, "1.0.0"));
		assertThrows(NullPointerException.class, () -> new OpenRpcInfo("Reference API", null));
		assertThrows(NullPointerException.class, () -> new OpenRpcContentDescriptor(null, Any.INSTANCE));
		assertThrows(NullPointerException.class, () -> new OpenRpcContentDescriptor("id", null));
		assertThrows(NullPointerException.class, () -> new OpenRpcMethod(null, List.of(), null, null));
		assertThrows(NullPointerException.class, () -> new OpenRpcMethod("user.get", null, null, null));
		assertThrows(NullPointerException.class, () -> OpenRpcDocument.of(null, List.of()));
		assertThrows(NullPointerException.class, () -> OpenRpcDocument.of(INFO, null));
	}

	// ---------------------------------------------------------------------------------------------

	private static String write(OpenRpcDocument document) {
		return JsonUtils.toJson(OpenRpcDocument.CODEC, document);
	}
}
