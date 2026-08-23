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
 * The reference fixture's flat {@code record} DTO — the mapping table's {@code record R} row.
 * <p>
 * Five components of five <i>different</i> pinned-subset shapes ({@code integer}, {@code string},
 * {@code integer}, {@code boolean}, {@code enum}), so the emitted
 * {@code {"type":"object","properties":{…},"required":[…]}} cannot be right by accident: a mapping that
 * confused two rows, or a generator that emitted properties in any order but the canonical constructor's,
 * produces a different document.
 * <p>
 * <b>Renaming or reordering a component here is a wire-format change</b>, not a Java refactor — the derived
 * codec's keys are the component names verbatim (feature 011), and the frozen reference document repeats
 * them.
 *
 * @param id       the identifier — the {@code long} row
 * @param customer who placed it — the {@code String} row
 * @param quantity how many — the {@code int} row, deliberately a <i>different</i> integral width from
 *                 {@code id}, since both collapse to {@code {"type":"integer"}}
 * @param express  whether it ships express — the {@code boolean} row
 * @param status   where it is — the {@code enum} row
 */
public record Order(long id, String customer, int quantity, boolean express, Status status) {
}
