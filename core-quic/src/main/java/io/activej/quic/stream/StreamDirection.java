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

package io.activej.quic.stream;

/**
 * Whether a stream carries data in both directions or only from its initiator (RFC 9000 §2.1).
 * Encoded as bit 1 of the stream id — see {@link StreamIds#isBidirectional(long)}.
 * <p>
 * Stream counts, {@code MAX_STREAMS} (RFC 9000 §19.11) and {@code STREAMS_BLOCKED}
 * (RFC 9000 §19.14) are all maintained per direction, which is why this is a named type rather than
 * a {@code boolean} parameter.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types
 * and Identifiers</a>
 */
public enum StreamDirection {
	/** Both endpoints have a send part and a receive part. */
	BIDIRECTIONAL,

	/** Only the initiator has a send part; only its peer has a receive part. */
	UNIDIRECTIONAL
}
