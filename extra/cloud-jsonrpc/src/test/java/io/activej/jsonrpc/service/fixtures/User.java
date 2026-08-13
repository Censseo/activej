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

/**
 * The worked example's DTO. A {@code record} on purpose: {@code JsonCodecFactory} derives its codec by
 * reflection since feature 011, so nothing here registers anything and the fixture stays a plain value.
 *
 * @param id   the identifier the wire calls {@code id}
 * @param name the display name
 */
public record User(long id, String name) {
}
