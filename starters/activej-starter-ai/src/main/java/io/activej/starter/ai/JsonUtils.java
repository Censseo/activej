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

package io.activej.starter.ai;

/**
 * Internal JSON utility methods for building and parsing JSON
 * without external dependencies.
 */
final class JsonUtils {

	private JsonUtils() {
	}

	static String extractJsonString(String json, String key) {
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx < 0) return "";
		int colonIdx = json.indexOf(':', idx + search.length());
		if (colonIdx < 0) return "";
		int start = json.indexOf('"', colonIdx + 1);
		if (start < 0) return "";
		start++;
		int end = json.indexOf('"', start);
		if (end < 0) return "";
		return json.substring(start, end);
	}

	static int extractJsonInt(String json, String key) {
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx < 0) return 0;
		int colonIdx = json.indexOf(':', idx + search.length());
		if (colonIdx < 0) return 0;
		int start = colonIdx + 1;
		while (start < json.length() && json.charAt(start) == ' ') start++;
		int end = start;
		while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
		if (start == end) return 0;
		try {
			return Integer.parseInt(json.substring(start, end));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static String escapeJson(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	static String extractValueAfterKey(String json, int keyIdx) {
		int colonIdx = json.indexOf(':', keyIdx);
		if (colonIdx < 0) return "";
		int start = json.indexOf('"', colonIdx + 1);
		if (start < 0) return "";
		start++;
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '\\' && i + 1 < json.length()) {
				char next = json.charAt(i + 1);
				switch (next) {
					case '"' -> { sb.append('"'); i++; }
					case '\\' -> { sb.append('\\'); i++; }
					case 'n' -> { sb.append('\n'); i++; }
					case 't' -> { sb.append('\t'); i++; }
					case 'r' -> { sb.append('\r'); i++; }
					default -> sb.append(c);
				}
			} else if (c == '"') {
				break;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
