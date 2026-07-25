package io.activej.serializer.util;

import io.activej.codegen.expression.Expression;

import static io.activej.codegen.expression.Expressions.*;

public class Utils {
	public static Expression initialCapacity(Expression initialSize) {
		return cast(staticCall(Utils.class, "computeInitialCapacity", cast(initialSize, long.class)), int.class);
	}

	public static long computeInitialCapacity(long initialSize) {
		return Math.min((initialSize + 2) / 3 * 4, Integer.MAX_VALUE - 8L);
	}

	public static Class<?> getArrayClass(Class<?> componentType) throws ClassNotFoundException {
		String name;
		if (componentType.isArray()) {
			name = "[" + componentType.getName();
		} else if (componentType == boolean.class) {
			name = "[Z";
		} else if (componentType == byte.class) {
			name = "[B";
		} else if (componentType == short.class) {
			name = "[S";
		} else if (componentType == char.class) {
			name = "[C";
		} else if (componentType == int.class) {
			name = "[I";
		} else if (componentType == long.class) {
			name = "[J";
		} else if (componentType == float.class) {
			name = "[F";
		} else if (componentType == double.class) {
			name = "[D";
		} else {
			name = "[L" + componentType.getName() + ";";
		}
		return Class.forName(name, false, componentType.getClassLoader());
	}
}
