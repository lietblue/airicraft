package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;

import java.util.Objects;

public record LlmChatMessage(
	String role,
	String content,
	LlmMessageKind kind,
	LlmImageAttachment imageAttachment,
	JsonElement rawContentOverride
) {
	public LlmChatMessage(String role, String content, LlmMessageKind kind, LlmImageAttachment imageAttachment) {
		this(role, content, kind, imageAttachment, null);
	}

	public LlmChatMessage {
		role = Objects.requireNonNull(role, "role");
		content = Objects.requireNonNull(content, "content");
		kind = Objects.requireNonNull(kind, "kind");
		rawContentOverride = rawContentOverride == null || rawContentOverride.isJsonNull()
			? null
			: rawContentOverride.deepCopy();
	}

	public static LlmChatMessage system(String content) {
		return new LlmChatMessage("system", content, LlmMessageKind.SYSTEM, null);
	}

	public static LlmChatMessage user(String content, LlmMessageKind kind) {
		return new LlmChatMessage("user", content, kind, null);
	}

	public static LlmChatMessage userWithImage(String content, LlmMessageKind kind, LlmImageAttachment imageAttachment) {
		return new LlmChatMessage("user", content, kind, Objects.requireNonNull(imageAttachment, "imageAttachment"));
	}

	public static LlmChatMessage assistant(String content) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null);
	}

	public static LlmChatMessage assistant(String content, JsonElement rawContentOverride) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null, rawContentOverride);
	}

	public boolean hasImageAttachment() {
		return imageAttachment != null;
	}
}
