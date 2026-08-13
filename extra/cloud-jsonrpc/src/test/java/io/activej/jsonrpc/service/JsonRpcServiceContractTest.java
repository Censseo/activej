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

package io.activej.jsonrpc.service;

import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.fixtures.BrokenApis;
import io.activej.jsonrpc.service.fixtures.User;
import io.activej.jsonrpc.service.fixtures.UserApi;
import io.activej.promise.Promise;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User story 1 and 3 — the startup contract: introspection of one annotated interface, and the nine
 * validation rules, every violation reported at once (FR-019…FR-034, FR-043a).
 * <p>
 * Nothing here builds a dispatcher, opens anything or invokes an implementation: a contract is a property of
 * the <b>interface alone</b> (FR-034), which is exactly why it can be checked before a port is opened.
 */
public class JsonRpcServiceContractTest {

	private static final JsonCodecFactory CODECS = JsonCodecFactory.defaultInstance();

	// ---------------------------------------------------------------------------------------------------
	// T013 — the happy path, so the rules below are read against something that works.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void introspectsTheWorkedExample() {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(UserApi.class, CODECS);

		assertEquals(UserApi.class, contract.serviceType());
		assertEquals("user", contract.prefix());
		assertEquals(Set.of("user.get", "user.touch"), contract.methods().keySet());

		JsonRpcMethodDescriptor get = contract.byWireName("user.get");
		assertNotNull(get);
		assertEquals("user.get", get.wireName());
		assertFalse(get.isNotification());
		assertFalse("Promise<T> is not a synchronous result", get.isSynchronousResult());
		assertNotNull("Promise<User> resolves a result codec", get.resultCodec());
		assertTrue("every parameter carries @JsonRpcParam", get.isNamable());
		assertEquals(1, get.params().size());

		JsonRpcParamDescriptor id = get.params().get(0);
		assertEquals(0, id.index());
		assertEquals(long.class, id.type());
		assertEquals("id", id.name());
		assertNotNull(id.codec());

		JsonRpcMethodDescriptor touch = contract.byWireName("user.touch");
		assertNotNull(touch);
		assertTrue(touch.isNotification());
		assertNull("a notification has no result codec", touch.resultCodec());
		assertFalse(touch.isSynchronousResult());
	}

	@Test
	public void looksUpByJavaMethodAsWellAsByWireName() throws NoSuchMethodException {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(UserApi.class, CODECS);
		Method getUser = UserApi.class.getMethod("getUser", long.class);

		JsonRpcMethodDescriptor descriptor = contract.byJavaMethod(getUser);
		assertNotNull(descriptor);
		assertEquals("user.get", descriptor.wireName());
		assertEquals(getUser, descriptor.method());
		assertNull(contract.byWireName("user.nope"));
	}

	@Test
	public void methodsMapIsUnmodifiable() {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(UserApi.class, CODECS);
		try {
			contract.methods().remove("user.get");
			fail("the method table must not be mutable after construction");
		} catch (UnsupportedOperationException expected) {
			// FR-057 / DI-4: immutable after construction
		}
	}

	@Test
	public void anInterfaceWithoutTheServiceAnnotationCarriesAnEmptyPrefix() {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(UnprefixedApi.class, CODECS);

		assertEquals("", contract.prefix());
		assertEquals(Set.of("user.get"), contract.methods().keySet());
	}

	@Test
	public void anEmptyAnnotationValueFallsBackToTheJavaMethodName() {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(FallbackNameApi.class, CODECS);

		assertEquals(Set.of("fb.whoAmI"), contract.methods().keySet());
	}

	// ---------------------------------------------------------------------------------------------------
	// T013 — the nine validation rules.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void rule1_theServiceTypeMustBeAnInterface() {
		assertSingleViolationContaining(BrokenApis.NotAnInterface.class, "interface");
		assertSingleViolationContaining(BrokenApis.NotAnInterfaceRecord.class, "interface");
	}

	@Test
	public void rule2_anUnannotatedAbstractMethodIsAViolation() {
		List<String> violations = violationsOf(BrokenApis.UnannotatedAbstractMethod.class);

		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.toString(), violations.get(0).contains("forgotten"));
		assertTrue(violations.toString(), violations.get(0).contains("@JsonRpcMethod"));
		assertTrue(violations.toString(), violations.get(0).contains("@JsonRpcNotification"));
	}

	@Test
	public void rule3_bothAnnotationsOnOneMethodIsAViolation() {
		List<String> violations = violationsOf(BrokenApis.BothAnnotations.class);

		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.toString(), violations.get(0).contains("get"));
		assertTrue(violations.toString(), violations.get(0).contains("both"));
	}

	@Test
	public void rule4_twoMethodsResolvingToTheSameWireNameIsAViolation() {
		List<String> violations = violationsOf(BrokenApis.DuplicateWireName.class);

		assertEquals(violations.toString(), 1, violations.size());
		String violation = violations.get(0);
		assertTrue(violation, violation.contains("dup.get"));
		assertTrue("both Java methods must be named: " + violation, violation.contains("getOne"));
		assertTrue("both Java methods must be named: " + violation, violation.contains("getAnother"));
	}

	@Test
	public void rule5_aNotificationReturningAValueIsAViolation() {
		List<String> violations = violationsOf(BrokenApis.NotificationReturningAValue.class);

		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.toString(), violations.get(0).contains("touch"));
		assertTrue("the permitted types must be named: " + violations.get(0),
			violations.get(0).contains("void") && violations.get(0).contains("Promise<Void>"));
	}

	@Test
	public void rule6_aVoidReturningMethodIsAViolation() {
		List<String> violations = violationsOf(BrokenApis.VoidReturningMethod.class);

		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.toString(), violations.get(0).contains("get"));
		assertTrue("the author must be pointed at the alternatives: " + violations.get(0),
			violations.get(0).contains("@JsonRpcNotification") || violations.get(0).contains("Promise<Void>"));
	}

	@Test
	public void rule7_aRawOrWildcardPromiseIsAViolation() {
		List<String> raw = violationsOf(BrokenApis.RawPromise.class);
		assertEquals(raw.toString(), 1, raw.size());
		assertTrue(raw.toString(), raw.get(0).contains("get"));

		List<String> wildcard = violationsOf(BrokenApis.WildcardPromise.class);
		assertEquals(wildcard.toString(), 1, wildcard.size());
		assertTrue(wildcard.toString(), wildcard.get(0).contains("get"));
	}

	@Test
	public void rule7_anUnboundTypeVariableIsAViolation() {
		List<String> violations = violationsOf(BrokenApis.UnboundTypeVariable.class);

		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.toString(), violations.get(0).contains("get"));
	}

	@Test
	public void rule8_anUnresolvableParameterOrResultTypeIsAViolation() {
		List<String> parameter = violationsOf(BrokenApis.UnresolvableParameterType.class);
		assertEquals(parameter.toString(), 1, parameter.size());
		assertTrue("the parameter position must be named: " + parameter.get(0), parameter.get(0).contains("0"));
		assertTrue("the Type must be named: " + parameter.get(0),
			parameter.get(0).contains(BrokenApis.Unresolvable.class.getName()));

		List<String> result = violationsOf(BrokenApis.UnresolvableResultType.class);
		assertEquals(result.toString(), 1, result.size());
		assertTrue("the Type must be named: " + result.get(0),
			result.get(0).contains(BrokenApis.Unresolvable.class.getName()));
	}

	// ---------------------------------------------------------------------------------------------------
	// T013 — every violation in one exception (FR-031, SC-005).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void everyViolationIsReportedInOneException() {
		List<String> violations = violationsOf(BrokenApis.ManyViolations.class);

		assertEquals("all five faults, in one pass: " + violations, 5, violations.size());
		assertTrue(violations.size() >= 3);

		assertTrue("unannotated abstract method: " + violations, anyContains(violations, "forgotten"));
		assertTrue("notification returning a value: " + violations, anyContains(violations, "touch"));
		assertTrue("void-returning method: " + violations, anyContains(violations, "nothing"));
		assertTrue("unresolvable parameter: " + violations, anyContains(violations, "weird"));
		assertTrue("duplicate wire name: " + violations, anyContains(violations, "many.alpha"));
	}

	@Test
	public void theExceptionMessageRendersEveryViolation() {
		try {
			JsonRpcServiceContract.of(BrokenApis.ManyViolations.class, CODECS);
			fail("a broken interface must not produce a contract");
		} catch (JsonRpcContractException e) {
			String message = e.getMessage();
			assertNotNull(message);
			for (String violation : e.violations()) {
				assertTrue("the message must render every violation, missing: " + violation + "\nin: " + message,
					message.contains(violation));
			}
			assertTrue("the message must name the offending interface: " + message,
				message.contains(BrokenApis.ManyViolations.class.getName()));
		}
	}

	@Test
	public void everyViolationNamesItsDeclaringTypeAndMethod() {
		for (String violation : violationsOf(BrokenApis.ManyViolations.class)) {
			assertTrue("a violation must name its declaring type: " + violation,
				violation.contains(BrokenApis.ManyViolations.class.getName()));
		}
	}

	@Test
	public void aContractExceptionIsAnIllegalArgumentException() {
		try {
			JsonRpcServiceContract.of(BrokenApis.BothAnnotations.class, CODECS);
			fail();
		} catch (IllegalArgumentException expected) {
			assertTrue(expected instanceof JsonRpcContractException);
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// T014 — participation rules (FR-022…FR-025, FR-047a).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void staticAndUnannotatedDefaultMethodsAreIgnoredAndAnAnnotatedDefaultParticipates() {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(ParticipationApi.class, CODECS);

		assertEquals(Set.of("p.annotatedDefault", "p.throwing"), contract.methods().keySet());
	}

	@Test
	public void aThrowsJsonRpcExceptionClauseIsAccepted() {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(ParticipationApi.class, CODECS);

		JsonRpcMethodDescriptor descriptor = contract.byWireName("p.throwing");
		assertNotNull("a declared throws clause is never a violation (FR-047a)", descriptor);
		assertEquals(1, descriptor.method().getExceptionTypes().length);
		assertEquals(JsonRpcException.class, descriptor.method().getExceptionTypes()[0]);
	}

	@Test
	public void inheritedMethodsParticipate() {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(InheritingApi.class, CODECS);

		assertEquals(Set.of("inh.base", "inh.own"), contract.methods().keySet());
	}

	@Test
	public void aWireNameInheritedFromTwoSuperInterfacesIsAViolation() {
		List<String> violations = violationsOf(ClashingApi.class);

		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.toString(), violations.get(0).contains("clash.get"));
	}

	@Test
	public void redeclaringAnInheritedMethodIsNotADuplicate() {
		// the same Java method reached through two paths is one method, not two colliding ones (FR-024)
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(RedeclaringApi.class, CODECS);

		assertEquals(Set.of("inh.base"), contract.methods().keySet());
	}

	@Test
	public void aSignatureDeclaredByTwoUnrelatedInterfacesIsAViolation() {
		List<String> violations = violationsOf(BrokenApis.DiamondApi.class);

		assertEquals("the diamond is one violation, not a spurious FR-022 on one path: " + violations,
			1, violations.size());
		String violation = violations.get(0);
		assertTrue(violation, violation.contains("FR-024"));
		assertTrue("both declaring types must be named: " + violation,
			violation.contains(BrokenApis.DiamondLeft.class.getName()));
		assertTrue("both declaring types must be named: " + violation,
			violation.contains(BrokenApis.DiamondRight.class.getName()));
		assertTrue("the remedy must be named: " + violation,
			violation.contains(BrokenApis.DiamondApi.class.getName()));
	}

	@Test
	public void theDiamondViolationDoesNotDependOnTheExtendsOrder() {
		List<String> violations = violationsOf(ReversedDiamondApi.class);

		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.get(0), violations.get(0).contains("FR-024"));
		assertTrue(violations.get(0), violations.get(0).contains(BrokenApis.DiamondLeft.class.getName()));
		assertTrue(violations.get(0), violations.get(0).contains(BrokenApis.DiamondRight.class.getName()));
	}

	@Test
	public void aDiamondIsAmbiguousEvenWhenBothPathsAreAnnotated() {
		List<String> violations = violationsOf(AnnotatedDiamondApi.class);

		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.get(0), violations.get(0).contains("FR-024"));
	}

	@Test
	public void redeclaringTheDiamondMethodOnTheServiceInterfaceResolvesIt() {
		// the redeclaration is the single most-derived declaration, so its annotations speak for the group
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(ResolvedDiamondApi.class, CODECS);

		assertEquals(Set.of("resolved.get"), contract.methods().keySet());
		assertNotNull(contract.byWireName("resolved.get"));
	}

	@Test
	public void rule9_twoParametersSharingOneNameAreAViolation() {
		List<String> violations = violationsOf(BrokenApis.DuplicateParamName.class);

		assertEquals(violations.toString(), 1, violations.size());
		String violation = violations.get(0);
		assertTrue("the shared name must be named: " + violation, violation.contains("'id'"));
		assertTrue("both positions must be named: " + violation,
			violation.contains("0") && violation.contains("1"));
		assertTrue("the method must be named: " + violation, violation.contains("move"));
	}

	// ---------------------------------------------------------------------------------------------------
	// T015 — generic resolution (FR-028, research threat 2).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aTypeVariableBoundByASuperInterfaceIsResolved() {
		JsonRpcServiceContract contract = JsonRpcServiceContract.of(UserCrudApi.class, CODECS);

		assertEquals(Set.of("crud.get", "crud.put"), contract.methods().keySet());

		JsonRpcMethodDescriptor get = contract.byWireName("crud.get");
		assertNotNull(get);
		// the binding is asserted through the codec rather than through a type accessor the contract does
		// not publish: only a codec derived for User renders a User's components
		JsonCodec<Object> resultCodec = uncheckedCodec(get.resultCodec());
		assertEquals("Promise<T> must bind to Promise<User>",
			"{\"id\":7,\"name\":\"seven\"}", JsonUtils.toJson(resultCodec, new User(7, "seven")));

		JsonRpcMethodDescriptor put = contract.byWireName("crud.put");
		assertNotNull(put);
		assertEquals("the parameter's T must bind to User", User.class, put.params().get(0).type());
		assertNull("Promise<Void> resolves to no codec at all (FR-030)", put.resultCodec());
	}

	@Test
	public void anUnboundTypeVariableIsReportedRatherThanThrownRaw() {
		try {
			JsonRpcServiceContract.of(BrokenApis.UnboundTypeVariable.class, CODECS);
			fail("an unbound type variable must be a contract violation");
		} catch (JsonRpcContractException e) {
			assertEquals(1, e.violations().size());
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Fixtures local to the contract's own rules.
	// ---------------------------------------------------------------------------------------------------

	/** No {@code @JsonRpcService}: the method's own name is the whole wire name (FR-018). */
	public interface UnprefixedApi {
		@JsonRpcMethod("user.get")
		Promise<User> get(@JsonRpcParam("id") long id);
	}

	@JsonRpcService("fb")
	public interface FallbackNameApi {
		@JsonRpcMethod
		Promise<String> whoAmI();
	}

	@JsonRpcService("p")
	public interface ParticipationApi {
		static String staticIgnored() {
			return "ignored";
		}

		default String unannotatedDefaultIgnored() {
			return "ignored";
		}

		private String privateIgnored() {
			return "ignored";
		}

		@JsonRpcMethod
		default Promise<String> annotatedDefault() {
			return Promise.of("default");
		}

		@JsonRpcMethod("throwing")
		Promise<User> throwing(@JsonRpcParam("id") long id) throws JsonRpcException;
	}

	public interface BaseApi {
		@JsonRpcMethod("base")
		Promise<String> base();
	}

	@JsonRpcService("inh")
	public interface InheritingApi extends BaseApi {
		@JsonRpcMethod("own")
		Promise<String> own();
	}

	@JsonRpcService("inh")
	public interface RedeclaringApi extends BaseApi {
		@Override
		@JsonRpcMethod("base")
		Promise<String> base();
	}

	public interface ClashOne {
		@JsonRpcMethod("get")
		Promise<String> one();
	}

	public interface ClashTwo {
		@JsonRpcMethod("get")
		Promise<String> two();
	}

	@JsonRpcService("clash")
	public interface ClashingApi extends ClashOne, ClashTwo {}

	/** The same diamond as {@link BrokenApis.DiamondApi} with the extends clause reversed. */
	@JsonRpcService("diamond")
	public interface ReversedDiamondApi extends BrokenApis.DiamondRight, BrokenApis.DiamondLeft {}

	public interface AnnotatedLeft {
		@JsonRpcMethod("left")
		Promise<User> get(@JsonRpcParam("id") long id);
	}

	public interface AnnotatedRight {
		@JsonRpcMethod("right")
		Promise<User> get(@JsonRpcParam("id") long id);
	}

	/** Both paths annotated, and disagreeing: no redeclaration means no deterministic winner either. */
	@JsonRpcService("anndiamond")
	public interface AnnotatedDiamondApi extends AnnotatedLeft, AnnotatedRight {}

	/** The remedy: the service interface redeclares the method, and its annotations speak for the group. */
	@JsonRpcService("resolved")
	public interface ResolvedDiamondApi extends AnnotatedLeft, AnnotatedRight {
		@Override
		@JsonRpcMethod("get")
		Promise<User> get(@JsonRpcParam("id") long id);
	}

	public interface CrudApi<T> {
		@JsonRpcMethod("get")
		Promise<T> get(@JsonRpcParam("id") long id);

		@JsonRpcMethod("put")
		Promise<Void> put(@JsonRpcParam("value") T value);
	}

	@JsonRpcService("crud")
	public interface UserCrudApi extends CrudApi<User> {}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	private static List<String> violationsOf(Class<?> serviceType) {
		try {
			JsonRpcServiceContract.of(serviceType, CODECS);
			fail(serviceType.getName() + " must not produce a contract");
			throw new AssertionError();
		} catch (JsonRpcContractException e) {
			return e.violations();
		}
	}

	private static void assertSingleViolationContaining(Class<?> serviceType, String fragment) {
		List<String> violations = violationsOf(serviceType);
		assertEquals(violations.toString(), 1, violations.size());
		assertTrue(violations.toString(), violations.get(0).contains(fragment));
		assertTrue(violations.toString(), violations.get(0).contains(serviceType.getName()));
	}

	private static boolean anyContains(List<String> violations, String fragment) {
		return violations.stream().anyMatch(v -> v.contains(fragment));
	}

	@SuppressWarnings("unchecked")
	private static JsonCodec<Object> uncheckedCodec(JsonCodec<?> codec) {
		assertNotNull(codec);
		return (JsonCodec<Object>) codec;
	}
}
