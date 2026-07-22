package ai.moeru.airicraft.agent.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSnapshotTest {
	@Test
	void companionActuationIsAllowedOnlyForSharedSessions() {
		assertFalse(snapshot(SessionMode.OUT_OF_WORLD).companionActuationAllowed());
		assertFalse(snapshot(SessionMode.SINGLEPLAYER_LOCAL).companionActuationAllowed());
		assertTrue(snapshot(SessionMode.SINGLEPLAYER_LAN_HOST).companionActuationAllowed());
		assertTrue(snapshot(SessionMode.REMOTE_MULTIPLAYER).companionActuationAllowed());
	}

	@Test
	void deadPlayerCannotActEvenInSharedSession() {
		SessionSnapshot dead = new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			10L,
			PlayerLifecycleState.DEAD
		);

		assertTrue(dead.requiresRespawn());
		assertFalse(dead.companionActuationAllowed());
	}

	@Test
	void lifecycleCannotRemainDeadWithoutALoadedWorld() {
		SessionSnapshot snapshot = new SessionSnapshot(
			SessionMode.OUT_OF_WORLD,
			true,
			false,
			null,
			false,
			0,
			10L,
			PlayerLifecycleState.DEAD
		);

		assertFalse(snapshot.requiresRespawn());
		assertTrue(snapshot.playerLifecycleState() == PlayerLifecycleState.UNAVAILABLE);
	}

	private static SessionSnapshot snapshot(SessionMode mode) {
		return new SessionSnapshot(
			mode,
			true,
			mode != SessionMode.OUT_OF_WORLD,
			mode == SessionMode.OUT_OF_WORLD ? null : "minecraft:overworld",
			mode == SessionMode.SINGLEPLAYER_LAN_HOST,
			mode == SessionMode.SINGLEPLAYER_LAN_HOST ? 25565 : 0,
			10L
		);
	}
}
