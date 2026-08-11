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

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * FR-017, research Decision 6 — the protocol exception carries a full error object and is stackless by the
 * {@code HttpException} idiom.
 */
public class JsonRpcExceptionTest {

	@Test
	public void itIsCheckedAndCarriesItsErrorObject() {
		JsonRpcError error = JsonRpcErrors.of(-1, "User not found");
		JsonRpcException e = new JsonRpcException(error);

		assertTrue("FR-017 requires a checked exception", Exception.class.isAssignableFrom(JsonRpcException.class));
		assertFalse(RuntimeException.class.isAssignableFrom(JsonRpcException.class));
		assertSame(error, e.getError());
		assertEquals("User not found", e.getMessage());
	}

	@Test
	public void aLocalMessageMayCarryDetailTheErrorObjectMayNot() {
		// FR-089's one-way valve: detail is allowed here, because a JsonRpcException stays local. The error
		// object it carries is the thing that goes on the wire, and its message is untouched.
		JsonRpcException e = new JsonRpcException(JsonRpcErrors.INTERNAL_ERROR, "user id 42 not in shard 3");

		assertEquals("user id 42 not in shard 3", e.getMessage());
		assertEquals("Internal error", e.getError().message());
		assertTrue(e.getError().data().isAbsent());
	}

	@Test
	public void aCauseIsCarried() {
		Exception cause = new IllegalStateException("boom");
		JsonRpcException e = new JsonRpcException(JsonRpcErrors.INTERNAL_ERROR, cause);
		assertSame(cause, e.getCause());
		assertSame(JsonRpcErrors.INTERNAL_ERROR, e.getError());

		JsonRpcException both = new JsonRpcException(JsonRpcErrors.INTERNAL_ERROR, "context", cause);
		assertEquals("context", both.getMessage());
		assertSame(cause, both.getCause());
	}

	@Test
	public void itIsStacklessByDefault() {
		JsonRpcException e = new JsonRpcException(JsonRpcErrors.PARSE_ERROR);

		assertFalse("the default must be off — this is a control-flow exception on a hot path",
			JsonRpcException.WITH_STACK_TRACE);
		assertSame("fillInStackTrace must return this, capturing nothing", e, e.fillInStackTrace());
		assertEquals(0, e.getStackTrace().length);
	}

	/**
	 * The other half of the idiom: {@code -DJsonRpcException.withStackTrace=true} must produce a real trace.
	 * <p>
	 * {@code WITH_STACK_TRACE} resolves into a {@code static final} at class-initialisation, so the only way
	 * to observe the other branch in one JVM is to initialise the class again — hence a child loader that
	 * defines this module's classes itself instead of delegating. Without this test the setting key is
	 * asserted by nobody and a typo in it would be invisible.
	 */
	@Test
	public void itCapturesARealTraceWhenTheSettingIsOn() throws Exception {
		System.setProperty("JsonRpcException.withStackTrace", "true");
		try {
			ClassLoader loader = new ModuleReloadingClassLoader(getClass().getClassLoader());

			Class<?> exceptionClass = Class.forName("io.activej.jsonrpc.JsonRpcException", true, loader);
			assertNotSame("the class must have been re-initialised, not reused",
				JsonRpcException.class, exceptionClass);
			assertTrue("the ApplicationSettings key must be <SimpleName>.withStackTrace",
				exceptionClass.getField("WITH_STACK_TRACE").getBoolean(null));

			Class<?> errorClass = Class.forName("io.activej.jsonrpc.JsonRpcError", true, loader);
			Object error = Class.forName("io.activej.jsonrpc.JsonRpcErrors", true, loader)
				.getField("PARSE_ERROR").get(null);

			Throwable thrown = (Throwable) exceptionClass.getConstructor(errorClass).newInstance(error);
			// Throwable#fillInStackTrace returns `this` in both branches, so the trace itself is the assertion
			assertTrue("a real trace was expected", thrown.getStackTrace().length > 0);
		} finally {
			System.clearProperty("JsonRpcException.withStackTrace");
		}
	}

	@Test
	public void theFullyQualifiedSettingKeyWorksToo() throws Exception {
		System.setProperty("io.activej.jsonrpc.JsonRpcException.withStackTrace", "true");
		try {
			ClassLoader loader = new ModuleReloadingClassLoader(getClass().getClassLoader());
			Class<?> exceptionClass = Class.forName("io.activej.jsonrpc.JsonRpcException", true, loader);
			assertTrue(exceptionClass.getField("WITH_STACK_TRACE").getBoolean(null));
		} finally {
			System.clearProperty("io.activej.jsonrpc.JsonRpcException.withStackTrace");
		}
	}

	/** Defines {@code io.activej.jsonrpc.*} itself so their static initialisers run again. */
	private static final class ModuleReloadingClassLoader extends ClassLoader {
		private ModuleReloadingClassLoader(ClassLoader parent) {
			super(parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.startsWith("io.activej.jsonrpc.") || name.endsWith("Test")) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					loaded = define(name);
				}
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}

		private Class<?> define(String name) throws ClassNotFoundException {
			String resource = name.replace('.', '/') + ".class";
			try (InputStream in = getParent().getResourceAsStream(resource)) {
				if (in == null) throw new ClassNotFoundException(name);
				byte[] bytes = in.readAllBytes();
				return defineClass(name, bytes, 0, bytes.length);
			} catch (IOException e) {
				throw new ClassNotFoundException(name, e);
			}
		}
	}
}
