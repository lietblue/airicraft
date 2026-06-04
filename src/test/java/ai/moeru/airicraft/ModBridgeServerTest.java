package ai.moeru.airicraft;

import net.minecraft.client.gui.screen.TitleScreen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModBridgeServerTest {
	@Test
	void currentScreenNameNormalizesTitleScreen() {
		assertEquals("TitleScreen", ModBridgeServer.currentScreenNameForStatus(new TitleScreen(), false));
	}

	@Test
	void currentScreenNameKeepsStableNullStates() {
		assertEquals("none", ModBridgeServer.currentScreenNameForStatus(null, false));
		assertEquals("in_game", ModBridgeServer.currentScreenNameForStatus(null, true));
	}
}
