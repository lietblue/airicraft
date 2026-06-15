package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.SmeltingOption;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ActionGraphExecutionInput(
	ActionResolverContext context,
	Map<String, Integer> observedInventory,
	boolean worldLoaded,
	boolean actuationAllowed,
	TaskTerminalEvent terminalTaskEvent,
	List<CraftingOpportunity> availableCrafts,
	List<SmeltingOption> availableSmelts,
	List<ActionFact> observedFacts
) {
	public ActionGraphExecutionInput(
		ActionResolverContext context,
		Map<String, Integer> observedInventory,
		boolean worldLoaded,
		boolean actuationAllowed,
		TaskTerminalEvent terminalTaskEvent
	) {
		this(context, observedInventory, worldLoaded, actuationAllowed, terminalTaskEvent, List.of(), List.of(), List.of());
	}

	public ActionGraphExecutionInput(
		ActionResolverContext context,
		Map<String, Integer> observedInventory,
		boolean worldLoaded,
		boolean actuationAllowed,
		TaskTerminalEvent terminalTaskEvent,
		List<CraftingOpportunity> availableCrafts
	) {
		this(context, observedInventory, worldLoaded, actuationAllowed, terminalTaskEvent, availableCrafts, List.of(), List.of());
	}

	public ActionGraphExecutionInput(
		ActionResolverContext context,
		Map<String, Integer> observedInventory,
		boolean worldLoaded,
		boolean actuationAllowed,
		TaskTerminalEvent terminalTaskEvent,
		List<CraftingOpportunity> availableCrafts,
		List<ActionFact> observedFacts
	) {
		this(context, observedInventory, worldLoaded, actuationAllowed, terminalTaskEvent, availableCrafts, List.of(), observedFacts);
	}

	public ActionGraphExecutionInput {
		context = Objects.requireNonNull(context, "context");
		observedInventory = copyInventory(observedInventory);
		availableCrafts = availableCrafts == null ? List.of() : List.copyOf(availableCrafts);
		availableSmelts = availableSmelts == null ? List.of() : List.copyOf(availableSmelts);
		observedFacts = observedFacts == null ? List.of() : List.copyOf(observedFacts);
	}

	private static Map<String, Integer> copyInventory(Map<String, Integer> inventory) {
		if (inventory == null || inventory.isEmpty()) {
			return Map.of();
		}
		LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
			if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
				copy.put(entry.getKey(), Math.max(0, entry.getValue()));
			}
		}
		return Collections.unmodifiableMap(copy);
	}
}
