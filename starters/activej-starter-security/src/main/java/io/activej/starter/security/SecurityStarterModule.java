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

package io.activej.starter.security;

import io.activej.common.builder.AbstractBuilder;
import io.activej.http.session.ISessionStore;
import io.activej.http.session.InMemorySessionStore;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLContext;

/**
 * A starter module that provides pre-configured security features
 * for ActiveJ HTTP applications, including session management and TLS support.
 * <p>
 * By default, {@link #create()} returns a module with sessions enabled
 * using the default session cookie name {@value DEFAULT_SESSION_COOKIE}.
 */
public final class SecurityStarterModule extends AbstractModule {

	public static final String DEFAULT_SESSION_COOKIE = "ACTIVEJ_SESSION_ID";

	private boolean sessionsEnabled;
	private String sessionIdCookie = DEFAULT_SESSION_COOKIE;

	private boolean tlsEnabled;
	private @Nullable SSLContext sslContext;

	private SecurityStarterModule() {
	}

	/**
	 * Creates a security module with sessions enabled using the default cookie name.
	 */
	public static SecurityStarterModule create() {
		return builder()
			.withSessions(DEFAULT_SESSION_COOKIE)
			.build();
	}

	public static Builder builder() {
		return new SecurityStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, SecurityStarterModule> {
		private Builder() {}

		/**
		 * Enables session management with the specified session ID cookie name.
		 *
		 * @param sessionIdCookie the name of the cookie used to store the session ID
		 */
		public Builder withSessions(String sessionIdCookie) {
			checkNotBuilt(this);
			SecurityStarterModule.this.sessionsEnabled = true;
			SecurityStarterModule.this.sessionIdCookie = sessionIdCookie;
			return this;
		}

		/**
		 * Enables TLS support with the given {@link SSLContext}.
		 *
		 * @param sslContext the SSL context to use for TLS connections
		 */
		public Builder withTls(SSLContext sslContext) {
			checkNotBuilt(this);
			SecurityStarterModule.this.tlsEnabled = true;
			SecurityStarterModule.this.sslContext = sslContext;
			return this;
		}

		@Override
		protected SecurityStarterModule doBuild() {
			return SecurityStarterModule.this;
		}
	}

	@Override
	protected void configure() {
		if (sessionsEnabled) {
			bind(String.class, "sessionIdCookie").toInstance(sessionIdCookie);
		}
		if (tlsEnabled && sslContext != null) {
			bind(SSLContext.class).toInstance(sslContext);
		}
	}

	@Provides
	ISessionStore<?> sessionStore(Reactor reactor) {
		return InMemorySessionStore.create(reactor);
	}
}
