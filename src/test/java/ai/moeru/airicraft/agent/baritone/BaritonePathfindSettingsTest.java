package ai.moeru.airicraft.agent.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritonePathfindSettingsTest {
	@Test
	void exposesAllNonJavaOnlySettingsWithoutBootstrappingMinecraft() {
		var settings = BaritonePathfindSettings.plannerSettingsSchema();
		@SuppressWarnings("unchecked")
		var properties = (java.util.Map<String, Object>) settings.get("properties");

		assertTrue(properties.containsKey("allowDownward"));
		assertTrue(properties.containsKey("allowParkour"));
	}
}
