package ai.moeru.airicraft.agent.actions;

import java.util.Objects;

public record ActionWatchSpec(
	ActionFactCondition condition,
	ActionFactIdentity sourceFactIdentity,
	long timeoutTicks,
	ActionWatchProgressKind progressKind,
	ActionWatchAnchor anchor
) {
	public ActionWatchSpec {
		condition = Objects.requireNonNull(condition, "condition");
		timeoutTicks = Math.max(1L, timeoutTicks);
		progressKind = progressKind == null ? ActionWatchProgressKind.NONE : progressKind;
	}
}
