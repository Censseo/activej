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

package io.activej.jsonrpc.schema.fixtures;

/**
 * The reference fixture's enum — the mapping table's {@code enum E} row.
 * <p>
 * <b>Declaration order is the contract.</b> The emitted schema is
 * {@code {"type":"string","enum":[…constants in declaration order…]}}, so reordering these constants is a
 * change to the frozen reference document, not a cosmetic edit. Four constants rather than two, so an
 * accidental sort is visible in the diff.
 */
public enum Status {
	NEW,
	PAID,
	SHIPPED,
	CANCELLED
}
