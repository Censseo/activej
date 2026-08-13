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
import io.activej.promise.Promise;
import io.activej.types.Types;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One annotated service interface, introspected once and validated in full (FR-019…FR-034).
 *
 * <h2>Total construction</h2>
 * {@link #of} either returns a contract in which <b>every</b> method is resolved — wire name, parameter
 * codecs, result codec — or throws {@link JsonRpcContractException} naming <b>every</b> fault it found. There
 * is no partial contract and no member resolved lazily at call time, which is what makes a broken interface a
 * startup failure rather than a failure on the first call that happens to hit it.
 *
 * <h2>Nothing is invoked</h2>
 * A contract is a property of the interface alone (FR-034): no implementation instance is required, and no
 * method of the service is called. That is why a dispatcher can validate before a transport exists, and why
 * a client can validate an interface it will only ever call remotely.
 *
 * <h2>Two lookup directions, one pass</h2>
 * The dispatcher arrives with a wire name; the proxy arrives with a {@link Method}. Both maps are built in
 * the same walk and neither is derived per call.
 *
 * <h2>Validation rules</h2>
 * <ol>
 *     <li>the service type is an interface (FR-021);</li>
 *     <li>every abstract method carries {@link JsonRpcMethod} or {@link JsonRpcNotification} (FR-022);</li>
 *     <li>never both on one method (FR-017);</li>
 *     <li>no two methods resolve to the same wire name (FR-025, FR-024);</li>
 *     <li>a notification declares {@code void} or {@code Promise<Void>} (FR-026);</li>
 *     <li>a method does not declare {@code void} (FR-027);</li>
 *     <li>no raw {@code Promise}, no {@code Promise<?>}, no unbound type variable (FR-028);</li>
 *     <li>every parameter type and the result type resolve to a codec (FR-029).</li>
 * </ol>
 * {@code static} and {@code private} interface methods are ignored, and a {@code default} method is ignored
 * unless it is annotated (FR-023). Methods inherited from super-interfaces participate (FR-024). A
 * {@code throws} clause — the only way to {@code throw} the checked
 * {@link io.activej.jsonrpc.JsonRpcException} directly — is accepted on any method and is never a violation
 * (FR-047a).
 */
public final class JsonRpcServiceContract {
	private final Class<?> serviceType;
	private final String prefix;
	private final Map<String, JsonRpcMethodDescriptor> methods;
	private final Map<Method, JsonRpcMethodDescriptor> byJavaMethod;

	private JsonRpcServiceContract(
		Class<?> serviceType, String prefix,
		Map<String, JsonRpcMethodDescriptor> methods, Map<Method, JsonRpcMethodDescriptor> byJavaMethod
	) {
		this.serviceType = serviceType;
		this.prefix = prefix;
		this.methods = Collections.unmodifiableMap(methods);
		this.byJavaMethod = Collections.unmodifiableMap(byJavaMethod);
	}

	/**
	 * Introspects and validates one service interface.
	 *
	 * @param serviceType  the interface to introspect; nothing on it is invoked
	 * @param codecFactory the factory every parameter and result type is resolved through. Registers
	 *                     nothing — a {@code record} is derived, anything else must already be resolvable
	 * @return a fully resolved contract
	 * @throws JsonRpcContractException if the interface breaks any rule; the exception carries <b>every</b>
	 *                                  violation, not the first (FR-031)
	 * @throws NullPointerException     if either argument is {@code null}
	 */
	public static JsonRpcServiceContract of(Class<?> serviceType, JsonCodecFactory codecFactory) {
		Objects.requireNonNull(serviceType, "serviceType");
		Objects.requireNonNull(codecFactory, "codecFactory");

		// Rule 1 first and alone: there is nothing sensible to say about the methods of a type that is not a
		// service type at all, and an annotation type answers isInterface() with true.
		if (!serviceType.isInterface() || serviceType.isAnnotation()) {
			throw new JsonRpcContractException(serviceType, List.of(
				serviceType.getName() + " is not an interface; a JSON-RPC service type must be an interface " +
				"(FR-021)"));
		}

		JsonRpcService service = serviceType.getAnnotation(JsonRpcService.class);
		String prefix = service == null ? "" : service.value();

		Map<TypeVariable<?>, Type> bindings = Types.getAllTypeBindings(serviceType);

		List<String> violations = new ArrayList<>();
		Map<String, JsonRpcMethodDescriptor> methods = new LinkedHashMap<>();
		Map<Method, JsonRpcMethodDescriptor> byJavaMethod = new HashMap<>();
		// the wire name is claimed as soon as it is computed, independently of whether the method's types
		// resolve — otherwise a collision between two methods that are each broken in some other way would
		// go unreported, and the author would fix one fault only to discover the next
		Map<String, Method> claimedNames = new LinkedHashMap<>();

		Map<String, List<Method>> bySignature = candidateMethods(serviceType);

		for (List<Method> overloads : bySignature.values()) {
			Method method = overloads.get(0);

			JsonRpcMethod methodAnnotation = method.getAnnotation(JsonRpcMethod.class);
			JsonRpcNotification notificationAnnotation = method.getAnnotation(JsonRpcNotification.class);

			if (methodAnnotation != null && notificationAnnotation != null) {
				violations.add(where(method) + " carries both @JsonRpcMethod and @JsonRpcNotification; " +
							   "a method is one or the other (FR-017)");
				continue;
			}
			if (methodAnnotation == null && notificationAnnotation == null) {
				// FR-023: a default method with no annotation is the author's own helper, not a wire method
				if (Modifier.isAbstract(method.getModifiers())) {
					violations.add(where(method) + " is abstract but carries neither @JsonRpcMethod nor " +
								   "@JsonRpcNotification (FR-022)");
				}
				continue;
			}

			boolean notification = notificationAnnotation != null;
			String own = notification ? notificationAnnotation.value() : methodAnnotation.value();
			if (own.isEmpty()) own = method.getName();
			String wireName = prefix.isEmpty() ? own : prefix + '.' + own;

			Method claimant = claimedNames.putIfAbsent(wireName, method);
			if (claimant != null) {
				violations.add("wire name '" + wireName + "' is claimed by two methods: " +
							   where(claimant) + " and " + where(method) + " (FR-025)");
				continue;
			}

			int before = violations.size();
			List<JsonRpcParamDescriptor> params =
				resolveParams(method, bindings, codecFactory, violations);
			ResultShape result = resolveResult(method, notification, bindings, codecFactory, violations);
			if (violations.size() != before) continue;

			JsonRpcMethodDescriptor descriptor = new JsonRpcMethodDescriptor(
				wireName, method, notification, params, result.codec, result.synchronous);
			methods.put(wireName, descriptor);
			// every reachable spelling of the same method maps to the same descriptor, so a proxy that hands
			// over a super-interface's Method finds it just as a re-declaring sub-interface's does
			for (Method alias : overloads) {
				byJavaMethod.put(alias, descriptor);
			}
		}

		if (!violations.isEmpty()) throw new JsonRpcContractException(serviceType, violations);

		return new JsonRpcServiceContract(serviceType, prefix, methods, byJavaMethod);
	}

	/** The interface this contract was built from. */
	public Class<?> serviceType() {
		return serviceType;
	}

	/** The {@link JsonRpcService} prefix, or the empty string when the annotation is absent (FR-018). */
	public String prefix() {
		return prefix;
	}

	/** Wire name to descriptor. Unmodifiable, in discovery order. */
	public Map<String, JsonRpcMethodDescriptor> methods() {
		return methods;
	}

	/** The set of wire names this contract resolves. Unmodifiable. */
	public Set<String> wireNames() {
		return methods.keySet();
	}

	/** The dispatcher's lookup direction. {@code null} when no method of this contract carries that name. */
	public @Nullable JsonRpcMethodDescriptor byWireName(String wireName) {
		return methods.get(wireName);
	}

	/** The proxy's lookup direction. {@code null} when the method is not a wire method of this contract. */
	public @Nullable JsonRpcMethodDescriptor byJavaMethod(Method method) {
		return byJavaMethod.get(method);
	}

	@Override
	public String toString() {
		return "JsonRpcServiceContract[" + serviceType.getName() + ", " + methods.size() + " methods]";
	}

	// ---------------------------------------------------------------------------------------------------
	// Introspection.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * Every candidate method of the interface, grouped by erased signature so that one Java method reached
	 * through two inheritance paths is one entry, not a self-collision. The most derived declaration heads
	 * each group; the rest are kept only as {@link #byJavaMethod} aliases.
	 * <p>
	 * {@code getMethods()} is the whole filter for {@code private} (it returns public members only) and the
	 * loop below is the filter for {@code static}, bridge and synthetic methods (FR-023).
	 */
	private static Map<String, List<Method>> candidateMethods(Class<?> serviceType) {
		List<Method> candidates = new ArrayList<>();
		for (Method method : serviceType.getMethods()) {
			if (method.isBridge() || method.isSynthetic()) continue;
			if (Modifier.isStatic(method.getModifiers())) continue;
			if (method.getDeclaringClass() == Object.class) continue;
			candidates.add(method);
		}
		// getMethods() has no specified order; a stable one makes the violation report reproducible
		candidates.sort(Comparator.comparing(Method::getName).thenComparing(JsonRpcServiceContract::signature));

		Map<String, List<Method>> bySignature = new LinkedHashMap<>();
		for (Method method : candidates) {
			List<Method> group = bySignature.computeIfAbsent(signature(method), key -> new ArrayList<>());
			// the most derived declaration heads the group: it is the one carrying the author's annotations
			if (!group.isEmpty() && group.get(0).getDeclaringClass().isAssignableFrom(method.getDeclaringClass())) {
				group.add(0, method);
			} else {
				group.add(method);
			}
		}
		return bySignature;
	}

	private static String signature(Method method) {
		StringBuilder sb = new StringBuilder(method.getName()).append('(');
		Class<?>[] parameterTypes = method.getParameterTypes();
		for (int i = 0; i < parameterTypes.length; i++) {
			if (i != 0) sb.append(',');
			sb.append(parameterTypes[i].getName());
		}
		return sb.append(')').toString();
	}

	private static List<JsonRpcParamDescriptor> resolveParams(
		Method method, Map<TypeVariable<?>, Type> bindings, JsonCodecFactory codecFactory,
		List<String> violations
	) {
		Type[] declared = method.getGenericParameterTypes();
		Annotation[][] annotations = method.getParameterAnnotations();
		List<JsonRpcParamDescriptor> params = new ArrayList<>(declared.length);

		for (int i = 0; i < declared.length; i++) {
			String name = paramName(annotations[i]);

			Type bound;
			try {
				bound = Types.bind(declared[i], bindings);
			} catch (RuntimeException e) {
				violations.add(where(method) + " parameter " + i + " declares an unbound type: " +
							   declared[i].getTypeName() + " (FR-028)");
				continue;
			}

			JsonCodec<?> codec;
			try {
				codec = codecFactory.resolve(bound);
			} catch (RuntimeException e) {
				violations.add(where(method) + " parameter " + i + ": no JSON codec could be resolved for " +
							   "type " + bound.getTypeName() + " (FR-029)");
				continue;
			}

			params.add(new JsonRpcParamDescriptor(i, bound, codec, name));
		}
		return params;
	}

	private static @Nullable String paramName(Annotation[] annotations) {
		for (Annotation annotation : annotations) {
			if (annotation instanceof JsonRpcParam param) return param.value();
		}
		return null;
	}

	/** The result codec plus whether the interface declared a bare {@code T} rather than a {@code Promise<T>}. */
	private record ResultShape(@Nullable JsonCodec<?> codec, boolean synchronous) {
		private static final ResultShape NONE = new ResultShape(null, false);
	}

	private static ResultShape resolveResult(
		Method method, boolean notification, Map<TypeVariable<?>, Type> bindings,
		JsonCodecFactory codecFactory, List<String> violations
	) {
		Type declared = method.getGenericReturnType();
		boolean isVoid = method.getReturnType() == void.class;

		Type bound;
		try {
			bound = Types.bind(declared, bindings);
		} catch (RuntimeException e) {
			violations.add(where(method) + " declares an unbound result type: " + declared.getTypeName() +
						   " (FR-028)");
			return ResultShape.NONE;
		}

		if (notification) {
			// FR-026: void or Promise<Void>, and nothing else — a notification has nowhere to put a value
			if (isVoid || isPromiseOf(bound, Void.class)) return ResultShape.NONE;
			violations.add(where(method) + " is a @JsonRpcNotification and must declare void or " +
						   "Promise<Void>, but declares " + declared.getTypeName() + " (FR-026)");
			return ResultShape.NONE;
		}

		if (isVoid) {
			violations.add(where(method) + " is a @JsonRpcMethod and must not return void; declare " +
						   "Promise<Void> or annotate it @JsonRpcNotification (FR-027)");
			return ResultShape.NONE;
		}

		if (bound == Promise.class) {
			violations.add(where(method) + " declares a raw Promise; the result codec cannot be resolved " +
						   "from an unparameterized type (FR-028)");
			return ResultShape.NONE;
		}

		if (bound instanceof ParameterizedType parameterized && parameterized.getRawType() == Promise.class) {
			Type argument = parameterized.getActualTypeArguments()[0];
			if (argument instanceof WildcardType) {
				violations.add(where(method) + " declares Promise<?>; a wildcard result carries no type to " +
							   "resolve a codec from (FR-028)");
				return ResultShape.NONE;
			}
			// FR-030: Promise<Void> is the JSON literal null on the wire and needs no codec for Void at all
			if (argument == Void.class) return ResultShape.NONE;
			return new ResultShape(resolveCodec(method, argument, codecFactory, violations), false);
		}

		// FR-056: a synchronous T is legal here; only the client proxy refuses it (FR-062)
		return new ResultShape(resolveCodec(method, bound, codecFactory, violations), true);
	}

	private static @Nullable JsonCodec<?> resolveCodec(
		Method method, Type type, JsonCodecFactory codecFactory, List<String> violations
	) {
		try {
			return codecFactory.resolve(type);
		} catch (RuntimeException e) {
			violations.add(where(method) + " result: no JSON codec could be resolved for type " +
						   type.getTypeName() + " (FR-029)");
			return null;
		}
	}

	private static boolean isPromiseOf(Type type, Class<?> argument) {
		return type instanceof ParameterizedType parameterized &&
			   parameterized.getRawType() == Promise.class &&
			   parameterized.getActualTypeArguments()[0] == argument;
	}

	/** {@code declaring.Type.method(paramTypes)} — every violation names both (FR-031). */
	private static String where(Method method) {
		StringBuilder sb = new StringBuilder(method.getDeclaringClass().getName())
			.append('.').append(method.getName()).append('(');
		Class<?>[] parameterTypes = method.getParameterTypes();
		for (int i = 0; i < parameterTypes.length; i++) {
			if (i != 0) sb.append(", ");
			sb.append(parameterTypes[i].getSimpleName());
		}
		return sb.append(')').toString();
	}
}
