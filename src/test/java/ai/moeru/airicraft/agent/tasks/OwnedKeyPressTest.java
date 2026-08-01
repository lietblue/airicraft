package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.option.KeyBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedKeyPressTest {
	@Test
	void inactiveReleasePreservesUserHeldKey() {
		KeyBinding jumpKey = new KeyBinding("key.airicraft.test.inactive_jump", 32, KeyBinding.MOVEMENT_CATEGORY);
		OwnedKeyPress control = new OwnedKeyPress();
		jumpKey.setPressed(true);

		control.release(jumpKey);

		assertTrue(jumpKey.isPressed());
	}

	@Test
	void repeatedReleasePreservesNewUserPressAfterOwnedRelease() {
		KeyBinding jumpKey = new KeyBinding("key.airicraft.test.owned_jump", 32, KeyBinding.MOVEMENT_CATEGORY);
		OwnedKeyPress control = new OwnedKeyPress();
		control.press(jumpKey);

		control.release(jumpKey);
		assertFalse(jumpKey.isPressed());

		jumpKey.setPressed(true);
		control.release(jumpKey);

		assertTrue(jumpKey.isPressed());
	}
}
