package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.semantic.SemanticContextUpdate;

import java.util.Objects;

public record PlannerContextEntry(
	PlannerContextEntryType type,
	String speaker,
	String text,
	long tick,
	long timestampMs,
	SemanticContextUpdate semanticUpdate
) {
	public PlannerContextEntry(
		PlannerContextEntryType type,
		String speaker,
		String text,
		long tick,
		long timestampMs
	) {
		this(type, speaker, text, tick, timestampMs, null);
	}

	public PlannerContextEntry {
		type = Objects.requireNonNull(type, "type");
		text = Objects.requireNonNull(text, "text");
	}

	public static PlannerContextEntry semanticNotice(SemanticContextUpdate update) {
		Objects.requireNonNull(update, "update");
		return new PlannerContextEntry(
			PlannerContextEntryType.NOTICE,
			null,
			update.text(),
			update.tick(),
			update.timestampMs(),
			update
		);
	}
}
