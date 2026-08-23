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

package io.activej.launchers.jsonrpc.tutorial;

/**
 * The tutorial's result type ({@code docs/cloud-extras/jsonrpc-tutorial.md} § Two types and an interface).
 * <p>
 * A {@code record}, so {@code JsonCodecFactory} derives its codec with no registration. Its component
 * names are the JSON keys and its canonical constructor order is the emitted member order — renaming or
 * reordering here is a wire-format change with no compile error anywhere (DI-6/DI-7).
 */
public record Greeting(String message, String language) {}
