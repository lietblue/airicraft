package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.option.KeyBinding;

final class OwnedKeyPress {
	private boolean ownsKey;

	void press(KeyBinding keyBinding) {
		if (keyBinding == null) {
			return;
		}
		keyBinding.setPressed(true);
		ownsKey = true;
	}

	void release(KeyBinding keyBinding) {
		if (!ownsKey) {
			return;
		}
		ownsKey = false;
		if (keyBinding != null) {
			keyBinding.setPressed(false);
		}
	}
}
