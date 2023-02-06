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

package io.activej.csp.dsl;

import io.activej.csp.consumer.ChannelConsumer;
import io.activej.csp.process.transformer.ChannelTransformer;
import io.activej.csp.supplier.ChannelSupplier;

public interface WithChannelTransformer<B, I, O> extends WithChannelInput<B, I>, WithChannelOutput<B, O>,
		ChannelTransformer<I, O> {

	@Override
	default ChannelSupplier<O> transform(ChannelSupplier<I> supplier) {
		getInput().set(supplier);
		return getOutput().getSupplier();
	}

	@Override
	default ChannelConsumer<I> transform(ChannelConsumer<O> consumer) {
		getOutput().set(consumer);
		return getInput().getConsumer();
	}

}
