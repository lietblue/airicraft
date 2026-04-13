package ai.moeru.airicraft.agent.llm;

import java.util.Objects;

public record LlmCallResult<T>(
	T payload,
	LlmUsageSnapshot usage,
	Integer statusCode,
	String responseModel
) {
	public LlmCallResult {
		usage = usage == null ? LlmUsageSnapshot.unknown() : usage;
	}

	public static <T> LlmCallResult<T> of(T payload, LlmUsageSnapshot usage) {
		return new LlmCallResult<>(payload, Objects.requireNonNullElse(usage, LlmUsageSnapshot.unknown()), null, null);
	}

	public static <T> LlmCallResult<T> of(T payload, LlmUsageSnapshot usage, Integer statusCode, String responseModel) {
		return new LlmCallResult<>(payload, Objects.requireNonNullElse(usage, LlmUsageSnapshot.unknown()), statusCode, responseModel);
	}
}
