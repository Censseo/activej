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

package io.activej.jsonrpc;

import io.activej.json.JsonCodecs;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FR-010…FR-013 — the sealed envelope message hierarchy.
 */
public class JsonRpcMessageTest {

	@Test
	public void permitsExactlyThreeKinds() {
		assertTrue("JsonRpcMessage must be sealed", JsonRpcMessage.class.isSealed());
		assertEquals(
			Set.of(JsonRpcRequest.class, JsonRpcNotification.class, JsonRpcResponse.class),
			Set.of(JsonRpcMessage.class.getPermittedSubclasses()));
	}

	@Test
	public void aRequestCarriesAnIdAMethodAndAPayload() {
		byte[] envelope = "{\"params\":[1,2,3]}".getBytes(UTF_8);
		JsonRpcPayload params = new JsonRpcPayload.Raw(envelope, 10, 17);
		JsonRpcRequest request = new JsonRpcRequest(new JsonRpcId.Num(1), "sum", params);

		assertEquals(new JsonRpcId.Num(1), request.id());
		assertEquals("sum", request.method());
		assertSame(params, request.params());

		// the two-argument form defaults params to absent, which is distinct from "params":null
		assertTrue(new JsonRpcRequest(JsonRpcId.NULL, "ping").params().isAbsent());
	}

	@Test
	public void aRequestRefusesNullsAndAnEmptyMethod() {
		JsonRpcPayload absent = JsonRpcPayload.absent();
		assertThrows(NullPointerException.class, () -> new JsonRpcRequest(null, "sum", absent));
		assertThrows(NullPointerException.class, () -> new JsonRpcRequest(JsonRpcId.NULL, null, absent));
		assertThrows(NullPointerException.class, () -> new JsonRpcRequest(JsonRpcId.NULL, "sum", null));
		assertThrows(IllegalArgumentException.class, () -> new JsonRpcRequest(JsonRpcId.NULL, "", absent));
	}

	@Test
	public void aNotificationHasNoIdComponentAtAll() {
		// FR-011: "a notification produces no response" is a property of the type, not a runtime check
		List<String> components = Arrays.stream(JsonRpcNotification.class.getRecordComponents())
			.map(RecordComponent::getName)
			.toList();
		assertEquals(List.of("method", "params"), components);

		JsonRpcNotification notification = new JsonRpcNotification("update", JsonRpcPayload.absent());
		assertEquals("update", notification.method());
		assertTrue(notification.params().isAbsent());
		assertTrue(new JsonRpcNotification("update").params().isAbsent());
	}

	@Test
	public void aNotificationRefusesNullsAndAnEmptyMethod() {
		JsonRpcPayload absent = JsonRpcPayload.absent();
		assertThrows(NullPointerException.class, () -> new JsonRpcNotification(null, absent));
		assertThrows(NullPointerException.class, () -> new JsonRpcNotification("update", null));
		assertThrows(IllegalArgumentException.class, () -> new JsonRpcNotification("", absent));
	}

	/**
	 * FR-011 — there is <b>no API path</b> from a notification to a response.
	 * <p>
	 * This is a type-system guarantee, so it is verified by the absence of a construction path rather than by
	 * a runtime refusal: no method of {@link JsonRpcNotification} yields a response or a request, and nothing
	 * that builds a response or a request accepts a notification.
	 */
	@Test
	public void thereIsNoApiPathFromANotificationToAResponse() {
		List<String> violations = new ArrayList<>();

		for (Method method : JsonRpcNotification.class.getMethods()) {
			if (method.getDeclaringClass() == Object.class) continue;
			Class<?> returned = method.getReturnType();
			if (JsonRpcResponse.class.isAssignableFrom(returned) || JsonRpcRequest.class.isAssignableFrom(returned)) {
				violations.add("JsonRpcNotification." + method.getName() + " yields a " + returned.getSimpleName());
			}
		}

		for (Class<?> target : List.of(JsonRpcResponse.class, JsonRpcRequest.class)) {
			List<Executable> constructionPaths = new ArrayList<>(List.of(target.getConstructors()));
			for (Method method : target.getMethods()) {
				if (Modifier.isStatic(method.getModifiers()) && target.isAssignableFrom(method.getReturnType())) {
					constructionPaths.add(method);
				}
			}
			for (Executable path : constructionPaths) {
				for (Class<?> parameter : path.getParameterTypes()) {
					if (JsonRpcNotification.class.isAssignableFrom(parameter)) {
						violations.add(target.getSimpleName() + " can be built from a notification: " + path);
					}
				}
			}
		}

		if (!violations.isEmpty()) {
			fail("a notification must have no route to a response document:\n\t" + String.join("\n\t", violations));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-013 — exactly one of result / error, checked unconditionally at construction.
	// A response is built from a peer's decoded data on the client side, so this guards untrusted input,
	// not a programming error, and must not be behind the CHECKS gate.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aResponseRefusesBothResultAndError() {
		JsonRpcPayload result = new JsonRpcPayload.Encoded<>(JsonCodecs.ofInteger(), 19);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> new JsonRpcResponse(new JsonRpcId.Num(1), result, JsonRpcErrors.INTERNAL_ERROR));
		assertTrue(e.getMessage(), e.getMessage().contains("both"));
	}

	@Test
	public void aResponseRefusesNeitherResultNorError() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> new JsonRpcResponse(new JsonRpcId.Num(1), JsonRpcPayload.absent(), null));
		assertTrue(e.getMessage(), e.getMessage().contains("neither"));
	}

	@Test
	public void aResponseRefusesANullIdOrNullResultPayload() {
		assertThrows(NullPointerException.class,
			() -> new JsonRpcResponse(null, JsonRpcPayload.absent(), JsonRpcErrors.INTERNAL_ERROR));
		assertThrows(NullPointerException.class,
			() -> new JsonRpcResponse(new JsonRpcId.Num(1), null, JsonRpcErrors.INTERNAL_ERROR));
	}

	@Test
	public void ofResultAndOfErrorAreTheErgonomicEntryPoints() {
		JsonRpcPayload result = new JsonRpcPayload.Encoded<>(JsonCodecs.ofInteger(), 19);
		JsonRpcResponse ok = JsonRpcResponse.ofResult(new JsonRpcId.Num(1), result);
		assertSame(result, ok.result());
		assertNull(ok.error());
		assertFalse(ok.isError());

		JsonRpcResponse failed = JsonRpcResponse.ofError(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR);
		assertTrue(failed.result().isAbsent());
		assertSame(JsonRpcErrors.PARSE_ERROR, failed.error());
		assertTrue(failed.isError());
		assertEquals(JsonRpcId.NULL, failed.id());
	}

	@Test
	public void aResultThatIsTheJsonLiteralNullIsStillAResult() {
		// §5 permits any JSON value as a result, "null" included — and a Raw over the four bytes `null` is a
		// present value, unlike JsonRpcPayload.absent(). Getting this wrong turns a legal response into a
		// "neither result nor error" refusal.
		byte[] envelope = "{\"result\":null}".getBytes(UTF_8);
		JsonRpcPayload literalNull = new JsonRpcPayload.Raw(envelope, 10, 14);
		assertEquals("null", new String(literalNull.toByteArray(), UTF_8));

		JsonRpcResponse response = JsonRpcResponse.ofResult(new JsonRpcId.Num(1), literalNull);
		assertFalse(response.isError());
		assertFalse(response.result().isAbsent());
	}

	@Test
	public void ofResultRefusesAnAbsentPayload() {
		assertThrows(IllegalArgumentException.class,
			() -> JsonRpcResponse.ofResult(new JsonRpcId.Num(1), JsonRpcPayload.absent()));
	}

	@Test
	public void ofErrorRefusesANullError() {
		assertThrows(NullPointerException.class, () -> JsonRpcResponse.ofError(JsonRpcId.NULL, null));
	}

	@Test
	public void messagesAreValues() {
		assertEquals(new JsonRpcNotification("ping"), new JsonRpcNotification("ping"));
		assertEquals(new JsonRpcNotification("ping").hashCode(), new JsonRpcNotification("ping").hashCode());
		assertNotEquals(new JsonRpcNotification("ping"), new JsonRpcNotification("pong"));

		assertEquals(
			new JsonRpcRequest(new JsonRpcId.Num(1), "sum"),
			new JsonRpcRequest(new JsonRpcId.Num(1), "sum"));
		// a request is never equal to a notification, however alike they look
		assertNotEquals(new JsonRpcRequest(JsonRpcId.NULL, "ping"), new JsonRpcNotification("ping"));

		assertEquals(
			JsonRpcResponse.ofError(new JsonRpcId.Num(1), JsonRpcErrors.PARSE_ERROR),
			JsonRpcResponse.ofError(new JsonRpcId.Num(1), JsonRpcErrors.PARSE_ERROR));
	}

	@Test
	public void aMessageIsExhaustivelySwitchable() {
		List<JsonRpcMessage> messages = List.of(
			new JsonRpcRequest(new JsonRpcId.Num(1), "sum"),
			new JsonRpcNotification("update"),
			JsonRpcResponse.ofError(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR));

		for (JsonRpcMessage message : messages) {
			String kind = switch (message) {
				case JsonRpcRequest ignored -> "request";
				case JsonRpcNotification ignored -> "notification";
				case JsonRpcResponse ignored -> "response";
			};
			assertTrue(Set.of("request", "notification", "response").contains(kind));
		}
	}
}
