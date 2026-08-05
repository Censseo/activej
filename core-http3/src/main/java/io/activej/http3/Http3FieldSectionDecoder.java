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

import io.activej.bytebuf.ByteBuf;
import io.activej.http3.qpack.QpackField;
import io.activej.promise.Promise;

import java.util.List;

/**
 * One field section decoded, or <b>held</b> until it can be — the seam an {@link Http3RequestStream}
 * uses instead of {@link io.activej.http3.qpack.QpackDecoder} when the connection above it can wait
 * (RFC 9204 §2.1.2, FR-033).
 * <p>
 * The QPACK codecs themselves stay synchronous (ADR-016): {@code qpack/} takes no {@code Reactor} and
 * returns no {@code Promise}, and {@code QpackDynamicDecoder.decodeOrBlock} reports a blocked section
 * as a value rather than waiting on one. This interface lives in the reactive package because
 * <i>waiting</i> is the connection's business — it is the connection that holds the section, bounds how
 * many and how long, and resumes it when the peer's encoder stream catches up.
 * <p>
 * Package-private for the reason {@link Http3EventListener} gives: it is how the connection that built
 * a request stream lends it a decoder it cannot build for itself, not a second public codec contract.
 */
@FunctionalInterface
interface Http3FieldSectionDecoder {
	/**
	 * Takes ownership of {@code encodedFieldSection} on every path, success and failure alike — the
	 * ownership rule {@code QpackDecoder.decode} already states.
	 * <p>
	 * The returned promise stays <b>pending</b> for as long as the section is blocked on an insertion
	 * that has not arrived. It fails with a {@link io.activej.http3.qpack.QpackException} carrying the
	 * RFC 9204 §6 code, whose {@code isConnectionError()} already carries the per-cause scope.
	 */
	Promise<List<QpackField>> decode(ByteBuf encodedFieldSection);
}
