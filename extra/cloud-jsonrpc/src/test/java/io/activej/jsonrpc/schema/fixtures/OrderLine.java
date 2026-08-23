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

import java.math.BigDecimal;

/**
 * A nested {@code record} DTO — the element type of {@link Inventory#lines()} and of the {@code List<T>} row.
 * <p>
 * Its three components are three different rows of the pinned subset ({@code string}, {@code integer},
 * {@code number}), so a schema that loses component types shows up immediately, and its order is the wire
 * contract feature 011 pinned: component names are the JSON keys and canonical constructor order is the
 * emitted member order.
 *
 * @param sku       the catalogue identifier — the {@code String} row
 * @param quantity  how many — the {@code int} row
 * @param unitPrice the price per unit — the {@code BigDecimal} row, which maps to {@code number}, not
 *                  {@code integer}
 */
public record OrderLine(String sku, int quantity, BigDecimal unitPrice) {
}
