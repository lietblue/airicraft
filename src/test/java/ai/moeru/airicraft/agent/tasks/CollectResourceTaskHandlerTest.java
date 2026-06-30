package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectResourceTaskHandlerTest {
	@Test
	void createsMineBlocksGoalForWoodLogs() {
		CollectResourceTaskHandler handler = new CollectResourceTaskHandler();

		var goal = handler.start(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), 120L);

		assertEquals(GoalType.MINE_BLOCKS, goal.type());
		assertEquals(new GoalMineSpec(List.of(
			"minecraft:oak_log",
			"minecraft:birch_log",
			"minecraft:spruce_log",
			"minecraft:jungle_log",
			"minecraft:acacia_log",
			"minecraft:dark_oak_log",
			"minecraft:mangrove_log",
			"minecraft:cherry_log",
			"minecraft:pale_oak_log"
		), 4), goal.mineSpec());
	}

	@Test
	void createsMineBlocksGoalForCatalogedOreResource() {
		CollectResourceTaskHandler handler = new CollectResourceTaskHandler();

		var goal = handler.start(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.RAW_IRON, 3), 120L);

		assertEquals(GoalType.MINE_BLOCKS, goal.type());
		assertEquals(new GoalMineSpec(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), 3), goal.mineSpec());
	}
}
