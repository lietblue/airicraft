package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class OpenAiCompatibleMessageContent {
	private OpenAiCompatibleMessageContent() {
	}

	public static String extract(JsonElement contentElement) {
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
}
