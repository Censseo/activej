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

import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.http3.testutil.StubDnsClient;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * T159: the {@link Http3EventListener} → {@code Inspector} bridge is built twice — once in
 * {@link Http3Server}, once in {@link Http3Client} — and the two copies are identical apart from which
 * component they name. Every listener method has a no-op default and the two {@code Inspector} types
 * share no supertype, so an event wired into one bridge and forgotten in the other compiles, passes
 * every other test, and surfaces only as a counter that is silently always zero.
 * <p>
 * The invariant this pins down is the one {@link Http3EventListener}'s own Javadoc states: it exists
 * <i>because</i> a server or a client cannot see these events for itself, so each of its methods is an
 * inspector event on both sides. Concretely:
 * <ul>
 *   <li>both bridges override <b>every</b> {@code Http3EventListener} method — not merely the same
 *       ones as each other, which two equally incomplete bridges would also satisfy;</li>
 *   <li>each override reaches the inspector method of the same name whose parameters are the
 *       component followed by the event's own, exactly once, with the arguments unchanged. The
 *       sentinel arguments differ per position, so a transposition is a failure rather than a
 *       coincidence.</li>
 * </ul>
 * Reflection over the anonymous classes is what makes this mechanical: a hand-written list of the
 * events would itself be a third copy to forget to update.
 * <p>
 * No {@code EventloopRule} — {@link ManualEventloop} installs its own reactor, per the module
 * convention. Nothing here runs the loop: both components are built and never started.
 */
public final class Http3InspectorBridgeParityTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final AsyncServlet SERVLET = request -> HttpResponse.ok200().toPromise();

	private ManualEventloop loop;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		loop.close();
	}

	@Test
	public void bothBridgesOverrideEveryEventListenerMethod() throws Exception {
		Set<String> events = signatures(eventMethods());
		Set<String> serverOverrides = signatures(overridesOf(bridgeOf(server(new Recorder()))));
		Set<String> clientOverrides = signatures(overridesOf(bridgeOf(client(new Recorder()))));

		assertEquals(
			"Http3Server's bridge must override every Http3EventListener method, or the events it " +
			"misses are counters that stay zero for the life of the server",
			events, serverOverrides);
		assertEquals(
			"Http3Client's bridge must override every Http3EventListener method, or the events it " +
			"misses are counters that stay zero for the life of the client",
			events, clientOverrides);
		assertEquals("the two bridges must stay identical in what they forward", serverOverrides, clientOverrides);
	}

	@Test
	public void everyServerEventReachesItsMatchingInspectorMethod() throws Exception {
		Recorder recorder = new Recorder();
		assertEveryEventForwards(server(recorder), Http3Server.class, Http3Server.Inspector.class, recorder);
	}

	@Test
	public void everyClientEventReachesItsMatchingInspectorMethod() throws Exception {
		Recorder recorder = new Recorder();
		assertEveryEventForwards(client(recorder), Http3Client.class, Http3Client.Inspector.class, recorder);
	}

	// ---------------------------------------------------------------- the assertion

	private static void assertEveryEventForwards(
		Object component, Class<?> componentType, Class<?> inspectorType, Recorder recorder
	) throws Exception {
		Http3EventListener bridge = bridgeOf(component);
		for (Method event : eventMethods()) {
			Object[] eventArgs = sentinels(event);

			Class<?>[] inspectorParameters = prepend(componentType, event.getParameterTypes(), Class[]::new);
			Method expected;
			try {
				expected = inspectorType.getMethod(event.getName(), inspectorParameters);
			} catch (NoSuchMethodException e) {
				throw new AssertionError(
					inspectorType.getTypeName() + " declares no counterpart of " + signature(event), e);
			}

			recorder.calls.clear();
			event.invoke(bridge, eventArgs);

			assertEquals(signature(event) + " must reach the inspector exactly once",
				1, recorder.calls.size());
			Call call = recorder.calls.get(0);
			assertEquals(signature(event) + " must reach the inspector method of the same name",
				expected, call.method());
			assertArrayEquals(signature(event) + " must forward the component and its arguments unchanged",
				prepend(component, eventArgs, Object[]::new), call.args());
		}
	}

	// ---------------------------------------------------------------- reflection

	private static Http3EventListener bridgeOf(Object component) throws Exception {
		List<Field> fields = new ArrayList<>();
		for (Field field : component.getClass().getDeclaredFields()) {
			if (field.getType() == Http3EventListener.class) fields.add(field);
		}
		assertEquals(component.getClass().getSimpleName() + " must hold exactly one Http3EventListener bridge",
			1, fields.size());
		Field field = fields.get(0);
		field.setAccessible(true);
		return (Http3EventListener) field.get(component);
	}

	private static List<Method> eventMethods() {
		List<Method> methods = new ArrayList<>();
		for (Method method : Http3EventListener.class.getDeclaredMethods()) {
			if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) continue;
			method.setAccessible(true);
			methods.add(method);
		}
		methods.sort(Comparator.comparing(Http3InspectorBridgeParityTest::signature));
		return methods;
	}

	private static List<Method> overridesOf(Http3EventListener bridge) {
		List<Method> methods = new ArrayList<>();
		for (Method method : bridge.getClass().getDeclaredMethods()) {
			if (method.isSynthetic() || method.isBridge()) continue;
			methods.add(method);
		}
		return methods;
	}

	private static Set<String> signatures(List<Method> methods) {
		return methods.stream().map(Http3InspectorBridgeParityTest::signature)
			.collect(TreeSet::new, Set::add, Set::addAll);
	}

	private static String signature(Method method) {
		return Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
			.collect(joining(", ", method.getName() + "(", ")"));
	}

	/**
	 * A distinct value per position, so a transposed pair of same-typed arguments — which is most of
	 * them, these events being counts and ids — fails rather than passing by symmetry.
	 */
	private static Object[] sentinels(Method event) {
		Class<?>[] types = event.getParameterTypes();
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++) {
			Class<?> type = types[i];
			if (type == long.class) args[i] = 1_000L + i;
			else if (type == int.class) args[i] = 100 + i;
			else if (type == boolean.class) args[i] = true;
			else if (type.isEnum()) args[i] = type.getEnumConstants()[type.getEnumConstants().length - 1];
			else throw new AssertionError("no sentinel for " + type.getTypeName() + " in " + signature(event));
		}
		return args;
	}

	private static <T> T[] prepend(T head, T[] tail, java.util.function.IntFunction<T[]> factory) {
		T[] result = factory.apply(tail.length + 1);
		result[0] = head;
		System.arraycopy(tail, 0, result, 1, tail.length);
		return result;
	}

	// ---------------------------------------------------------------- fixtures

	private Http3Server server(Recorder recorder) {
		return Http3Server.builder(reactor(), SERVLET)
			.withInspector((Http3Server.Inspector) recorder.proxy(Http3Server.Inspector.class))
			.build();
	}

	private Http3Client client(Recorder recorder) {
		return Http3Client.builder(reactor(), new StubDnsClient())
			.withInspector((Http3Client.Inspector) recorder.proxy(Http3Client.Inspector.class))
			.build();
	}

	private NioReactor reactor() {
		return loop.eventloop();
	}

	private record Call(Method method, Object[] args) {}

	private static final class Recorder implements InvocationHandler {
		private final List<Call> calls = new ArrayList<>();

		Object proxy(Class<?> inspectorType) {
			return Proxy.newProxyInstance(inspectorType.getClassLoader(), new Class<?>[]{inspectorType}, this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> "recordingInspector";
				};
			}
			if (!method.getName().startsWith("on")) return null;
			calls.add(new Call(method, args));
			return null;
		}
	}
}
