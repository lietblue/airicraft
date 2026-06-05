package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReturnToSurfaceStepArgsTest {
	@Test
	void defaultsFillerBlocksWhenListIsMissingOrBlank() {
		assertEquals(ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS, ReturnToSurfaceStepArgs.normalizeFillerBlockIds(null));
		assertEquals(ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS, ReturnToSurfaceStepArgs.normalizeFillerBlockIds(List.of(" ", "")));
	}

	@Test
	void trimsAndDeduplicatesFillerBlocks() {
		assertEquals(
			List.of("minecraft:dirt", "minecraft:cobblestone"),
			ReturnToSurfaceStepArgs.normalizeFillerBlockIds(List.of(" minecraft:dirt ", "minecraft:dirt", "minecraft:cobblestone"))
		);
	}
}
