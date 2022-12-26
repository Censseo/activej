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

package io.activej.async.process;

import io.activej.common.Checks;
import io.activej.common.recycle.Recyclers;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static io.activej.common.Checks.checkState;

public abstract class AbstractAsyncCloseable implements AsyncCloseable {
	private static final boolean CHECK = Checks.isEnabled(AbstractAsyncCloseable.class);

	protected final Reactor reactor = Reactor.getCurrentReactor();

	private @Nullable AsyncCloseable closeable;

	private Exception exception;

	public Exception getException() {
		return exception;
	}

	public final void setCloseable(@Nullable AsyncCloseable closeable) {
		this.closeable = closeable;
	}

	protected void onClosed(@NotNull Exception e) {
	}

	protected void onCleanup() {
	}

	@Override
	public final void closeEx(@NotNull Exception e) {
		if (CHECK) checkState(reactor.inReactorThread(), "Not in eventloop thread");
		if (isClosed()) return;
		exception = e;
		reactor.post(this::onCleanup);
		onClosed(e);
		if (closeable != null) {
			closeable.closeEx(e);
		}
	}

	public final boolean isClosed() {
		return exception != null;
	}

	public final <T> @NotNull Promise<T> sanitize(Promise<T> promise) {
		return promise.async()
				.then(this::doSanitize);
	}

	protected final <T> @NotNull Promise<T> doSanitize(T value, @Nullable Exception e) {
		if (exception != null) {
			Recyclers.recycle(value);
			if (value instanceof AsyncCloseable) {
				((AsyncCloseable) value).closeEx(exception);
			}
			return Promise.ofException(exception);
		}
		if (e == null) {
			return Promise.of(value);
		} else {
			closeEx(e);
			return Promise.ofException(e);
		}
	}

}
