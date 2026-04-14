package ai.moeru.airicraft.agent.shell;

import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;

public record PlannerShellComponents(
	CurrentViewVisionService visionService,
	DialogueRuntime dialogueRuntime,
	PlannerShellJournal plannerJournal
) {
}
