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

import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.service.fixtures.BrokenApis;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User story 3 — every diagnostic names enough to act on: the declaring type and method, and for the two
 * violations with more to say, the extra identifying detail (FR-029, FR-031).
 */
public class JsonRpcContractDiagnosticsTest {

	private static final JsonCodecFactory CODECS = JsonCodecFactory.defaultInstance();

	@Test
	public void everyViolationNamesItsDeclaringTypeAndMethod() {
		List<String> violations = violationsOf(BrokenApis.UnannotatedAbstractMethod.class);
		assertEquals(1, violations.size());
		String violation = violations.get(0);
		assertTrue("must name the declaring type: " + violation,
			violation.contains(BrokenApis.UnannotatedAbstractMethod.class.getName()));
		assertTrue("must name the method: " + violation, violation.contains("forgotten"));
	}

	@Test
	public void duplicateWireNameNamesBothMethodsAndTheCollidingName() {
		List<String> violations = violationsOf(BrokenApis.DuplicateWireName.class);
		assertEquals(1, violations.size());
		String violation = violations.get(0);
		assertTrue("must name the colliding wire name: " + violation, violation.contains("'dup.get'"));
		assertTrue("must name the first method: " + violation, violation.contains("getOne"));
		assertTrue("must name the second method: " + violation, violation.contains("getAnother"));
		assertTrue("must name the declaring type: " + violation,
			violation.contains(BrokenApis.DuplicateWireName.class.getName()));
	}

	@Test
	public void unresolvableCodecNamesMethodParameterPositionAndType() {
		List<String> violations = violationsOf(BrokenApis.UnresolvableParameterType.class);
		assertEquals(1, violations.size());
		String violation = violations.get(0);
		assertTrue("must name the method: " + violation, violation.contains("get"));
		assertTrue("must name the parameter position: " + violation, violation.contains("parameter 0"));
		assertTrue("must name the unresolvable type: " + violation,
			violation.contains(BrokenApis.Unresolvable.class.getName()));
	}

	@Test
	public void unresolvableResultCodecNamesMethodAndType() {
		List<String> violations = violationsOf(BrokenApis.UnresolvableResultType.class);
		assertEquals(1, violations.size());
		String violation = violations.get(0);
		assertTrue("must name the method: " + violation, violation.contains("get"));
		assertTrue("must name the unresolvable type: " + violation,
			violation.contains(BrokenApis.Unresolvable.class.getName()));
	}

	private static List<String> violationsOf(Class<?> serviceType) {
		try {
			JsonRpcServiceContract.of(serviceType, CODECS);
			fail("expected " + serviceType.getName() + " to be rejected");
			throw new AssertionError();
		} catch (JsonRpcContractException e) {
			return e.violations();
		}
	}
}
