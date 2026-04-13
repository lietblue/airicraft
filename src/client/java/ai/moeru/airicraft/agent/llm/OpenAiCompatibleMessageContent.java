package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

final class OpenAiCompatibleMessageContent {
	private OpenAiCompatibleMessageContent() {
	}

	static String extract(JsonElement contentElement) {
		if (contentElement == null || contentElement.isJsonNull()) {
			return "";
		}
		if (contentElement.isJsonPrimitive()) {
			return contentElement.getAsString();
		}
		if (contentElement.isJsonArray()) {
			StringBuilder builder = new StringBuilder();
			for (JsonElement part : contentElement.getAsJsonArray()) {
				if (!part.isJsonObject()) {
					continue;
				}
				JsonObject object = part.getAsJsonObject();
				if (object.has("text")) {
					builder.append(object.get("text").getAsString());
				}
			}
			return builder.toString();
		}
		return contentElement.toString();
	}

	static JsonObject extractJsonObject(JsonElement contentElement) {
		return parseJsonObject(extract(contentElement));
	}

	static JsonObject parseJsonObject(String rawContent) {
		String content = rawContent == null ? "" : rawContent.trim();
		if (content.isEmpty()) {
			throw new JsonParseException("Missing message content");
		}
		try {
			return JsonParser.parseString(content).getAsJsonObject();
		}
		catch (IllegalStateException | JsonParseException exception) {
			String embeddedObject = firstBalancedJsonObject(content);
			if (embeddedObject == null) {
				throw exception;
			}
			return JsonParser.parseString(embeddedObject).getAsJsonObject();
		}
	}

	private static String firstBalancedJsonObject(String content) {
		int start = -1;
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;

		for (int index = 0; index < content.length(); index++) {
			char character = content.charAt(index);
			if (start < 0) {
				if (character == '{') {
					start = index;
					depth = 1;
				}
				continue;
			}

			if (inString) {
				if (escaped) {
					escaped = false;
					continue;
				}
				if (character == '\\') {
					escaped = true;
					continue;
				}
				if (character == '"') {
					inString = false;
				}
				continue;
			}

			if (character == '"') {
				inString = true;
				continue;
			}
			if (character == '{') {
				depth++;
				continue;
			}
			if (character == '}') {
				depth--;
				if (depth == 0) {
					return content.substring(start, index + 1);
				}
				if (depth < 0) {
					return null;
				}
			}
		}
		return null;
	}
}
