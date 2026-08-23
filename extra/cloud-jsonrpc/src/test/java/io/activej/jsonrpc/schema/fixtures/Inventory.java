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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The container-carrying {@code record} DTO: every collection row of the pinned subset nested <i>inside</i> an
 * object schema, which is where a mapping that only handles containers at the top level fails.
 * <p>
 * {@code tags} is a <b>reference</b> array. Primitive arrays ({@code int[]}, {@code byte[]}) are deliberately
 * absent: {@code JsonCodecFactory} does not resolve them — they are not assignable to {@code Object[]} and
 * fall through to the {@code Object} fallback — so one would be rejected by contract rule 8 before any schema
 * was ever asked for.
 *
 * @param tags       free-form labels — the {@code T[]} row
 * @param lines      the order lines — the {@code List<T>} row, over a nested {@code record}
 * @param statuses   the statuses present — the {@code Set<T>} row, over an {@code enum}
 * @param quantities stock per SKU — the {@code Map<String,V>} row, the only map shape inside the subset
 */
public record Inventory(String[] tags, List<OrderLine> lines, Set<Status> statuses, Map<String, Integer> quantities) {
}
