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

package io.activej.json.otherpackage;

/**
 * The only handle {@code RecordDerivationTest} has on a <b>non-public record declared outside
 * {@code io.activej.json}</b>. The record itself is package-private and top-level in this file, so no
 * test in another package can name it, construct it or reach its accessor — which is precisely the
 * access situation derivation has to work in.
 *
 * <p>This package exists for that one reason. It is not a second home for JSON fixtures.
 *
 * <p><b>Threat that travels with any result taken from here</b>: only <i>package</i> access is
 * exercised. This repository contains no {@code module-info.java}, so the genuinely non-exported JPMS
 * case — where {@code setAccessible} would raise {@code InaccessibleObjectException} — is reproduced
 * nowhere and remains unmeasured.
 */
public final class OtherPackageRecords {
	private OtherPackageRecords() {}

	/**
	 * The declaration under test, spelled out because the caller cannot see it:
	 * {@code record Secret(int n, String label) {}} — package-private, in this package.
	 */
	public static Class<?> secretClass() {
		return Secret.class;
	}

	public static Object secret(int n, String label) {
		return new Secret(n, label);
	}
}

/** Package-private and top-level on purpose — see {@link OtherPackageRecords}. */
record Secret(int n, String label) {}
