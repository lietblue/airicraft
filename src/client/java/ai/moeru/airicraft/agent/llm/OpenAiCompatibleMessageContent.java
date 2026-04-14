package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class OpenAiCompatibleMessageContent {
	private static final Pattern THOUGHT_BLOCK_PATTERN = Pattern.compile("(?is)<thought>.*?</thought>");

	private OpenAiCompatibleMessageContent() {
	}

	public static String extract(JsonElement contentElement) {
		return extractVisibleText(contentElement);
	}

	public static JsonElement rawContentForReplay(JsonElement contentElement) {
		if (contentElement == null || contentElement.isJsonNull()) {
			return null;
		}
		return contentElement.deepCopy();
	}

	public static String extractVisibleText(JsonElement contentElement) {
		if (contentElement == null || contentElement.isJsonNull()) {
			return "";
		}
		if (contentElement.isJsonPrimitive()) {
			return filterThoughtMarkup(contentElement.getAsString());
		}
		if (contentElement.isJsonArray()) {
			StringBuilder builder = new StringBuilder();
			for (JsonElement part : contentElement.getAsJsonArray()) {
				if (!part.isJsonObject()) {
					continue;
				}
				String text = extractPartText(part.getAsJsonObject());
				if (!text.isBlank()) {
					builder.append(text);
				}
			}
			return builder.toString().strip();
		}
		if (contentElement.isJsonObject()) {
			return extractPartText(contentElement.getAsJsonObject());
		}
		return filterThoughtMarkup(contentElement.toString());
	}

	public static Optional<JsonObject> extractJsonObject(JsonElement contentElement) {
		Optional<JsonObject> direct = parseJsonObjectCandidate(extractVisibleText(contentElement));
		if (direct.isPresent()) {
			return direct;
		}
		if (contentElement == null || contentElement.isJsonNull()) {
			return Optional.empty();
		}
		if (contentElement.isJsonPrimitive()) {
			return parseJsonObjectCandidate(contentElement.getAsString());
		}
		if (contentElement.isJsonArray()) {
			for (JsonElement part : contentElement.getAsJsonArray()) {
				if (!part.isJsonObject()) {
					continue;
				}
				JsonObject object = part.getAsJsonObject();
				if (isThoughtPart(object)) {
					continue;
				}
				Optional<JsonObject> parsed = parseJsonObjectCandidate(extractPartText(object));
				if (parsed.isPresent()) {
					return parsed;
				}
			}
		}
		return parseJsonObjectCandidate(contentElement.toString());
	}

	private static String extractPartText(JsonObject object) {
		if (object == null || isThoughtPart(object) || !object.has("text") || object.get("text").isJsonNull()) {
			return "";
		}
		return filterThoughtMarkup(object.get("text").getAsString());
	}

	private static boolean isThoughtPart(JsonObject object) {
		if (object == null) {
			return false;
		}
		if (object.has("thought") && !object.get("thought").isJsonNull() && object.get("thought").getAsBoolean()) {
			return true;
		}
		if (object.has("thought_signature") || object.has("thoughtSignature")) {
			return true;
		}
		if (object.has("type") && !object.get("type").isJsonNull()) {
			String type = object.get("type").getAsString().toLowerCase(Locale.ROOT);
			return "reasoning".equals(type)
				|| "reasoning_content".equals(type)
				|| "thought".equals(type)
				|| "thinking".equals(type);
		}
		return false;
	}

	private static String filterThoughtMarkup(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String stripped = THOUGHT_BLOCK_PATTERN.matcher(text).replaceAll("").strip();
		if (stripped.startsWith("<thought>")) {
			int closingIndex = stripped.indexOf("</thought>");
			if (closingIndex >= 0) {
				return stripped.substring(closingIndex + "</thought>".length()).strip();
			}
			return "";
		}
		return stripped;
	}

	private static Optional<JsonObject> parseJsonObjectCandidate(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String stripped = OpenAiCompatibleLlmBackend.stripMarkdownCodeFences(text).strip();
		Optional<JsonObject> direct = tryParseJsonObject(stripped);
		if (direct.isPresent()) {
			return direct;
		}
		return findLongestJsonObjectSubstring(stripped);
	}

	private static Optional<JsonObject> tryParseJsonObject(String text) {
		try {
			return Optional.of(JsonParser.parseString(text).getAsJsonObject());
		}
		catch (IllegalStateException | JsonParseException exception) {
			return Optional.empty();
		}
	}

	private static Optional<JsonObject> findLongestJsonObjectSubstring(String text) {
		JsonObject best = null;
		int bestLength = -1;
		for (int index = 0; index < text.length(); index++) {
			if (text.charAt(index) != '{') {
				continue;
			}
			int endIndex = findMatchingBrace(text, index);
			if (endIndex < 0) {
				continue;
			}
			String candidate = text.substring(index, endIndex + 1);
			Optional<JsonObject> parsed = tryParseJsonObject(candidate);
			if (parsed.isPresent() && candidate.length() > bestLength) {
				best = parsed.get();
				bestLength = candidate.length();
			}
		}
		return Optional.ofNullable(best);
	}

	private static int findMatchingBrace(String text, int startIndex) {
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int index = startIndex; index < text.length(); index++) {
			char current = text.charAt(index);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (current == '\\') {
				escaped = true;
				continue;
			}
			if (current == '"') {
				inString = !inString;
				continue;
			}
			if (inString) {
				continue;
			}
			if (current == '{') {
				depth++;
				continue;
			}
			if (current != '}') {
				continue;
			}
			depth--;
			if (depth == 0) {
				return index;
			}
		}
		return -1;
	}
}
