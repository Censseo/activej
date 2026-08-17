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

package io.activej.launchers.jsonrpc;

/**
 * One {@code (service interface, implementation)} pair contributed into the launcher's
 * {@code JsonRpcServiceBinding} set — the DI form of "more than one interface" (FR-012).
 * <p>
 * {@link JsonRpcModule} iterates the set and calls {@code JsonRpcDispatcher.Builder.withService} once per
 * element. The {@code isInstance} relationship between the pair is validated there, and a wire-name
 * collision between two interfaces is refused by the existing dispatcher builder at {@code build()} — no
 * second check exists.
 */
public record JsonRpcServiceBinding(Class<?> serviceType, Object implementation) {}
