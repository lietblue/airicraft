package ai.moeru.airicraft.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeExtensionRegistryTest {
	@AfterEach
	void clearRoutes() {
		BridgeExtensionRegistry.clearForTests();
	}

	@Test
	void startsEmptyWithoutAddonRegistrations() {
		assertTrue(BridgeExtensionRegistry.routes().isEmpty());
	}

	@Test
	void registersNormalizedRoutesAndRejectsDuplicates() {
		BridgeRoute route = context -> context.writeJson(200, java.util.Map.of("ok", true));

		BridgeExtensionRegistry.register("v1/evaluation/status", route);

		assertEquals("/v1/evaluation/status", BridgeExtensionRegistry.routes().getFirst().path());
		assertThrows(
			IllegalArgumentException.class,
			() -> BridgeExtensionRegistry.register("/v1/evaluation/status", route)
		);
	}
}
