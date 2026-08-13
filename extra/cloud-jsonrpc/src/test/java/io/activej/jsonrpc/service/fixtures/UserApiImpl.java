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

package io.activej.jsonrpc.service.fixtures;

import io.activej.promise.Promise;

import java.util.ArrayList;
import java.util.List;

/**
 * The worked example's implementation. It records every invocation so a test can assert not only <i>what</i>
 * came back but <i>whether the implementation was reached at all</i> — which is what an unknown-method test
 * has to prove (FR-041).
 */
public final class UserApiImpl implements UserApi {
	private final List<String> invocations = new ArrayList<>();

	@Override
	public Promise<User> getUser(long id) {
		invocations.add("getUser(" + id + ')');
		return Promise.of(new User(id, "user-" + id));
	}

	@Override
	public void touch(long id) {
		invocations.add("touch(" + id + ')');
	}

	/** Every invocation so far, in order, rendered as {@code name(args)}. */
	public List<String> invocations() {
		return invocations;
	}
}
