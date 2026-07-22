package ai.moeru.airicraft.agent.session;

public record SessionSnapshot(
	SessionMode mode,
	boolean clientBooted,
	boolean worldLoaded,
	String dimensionId,
	boolean lanPublished,
	int lanPort,
	long tickCount,
	PlayerLifecycleState playerLifecycleState
) {
	public SessionSnapshot(
		SessionMode mode,
		boolean clientBooted,
		boolean worldLoaded,
		String dimensionId,
		boolean lanPublished,
		int lanPort,
		long tickCount
	) {
		this(
			mode,
			clientBooted,
			worldLoaded,
			dimensionId,
			lanPublished,
			lanPort,
			tickCount,
			worldLoaded ? PlayerLifecycleState.ALIVE : PlayerLifecycleState.UNAVAILABLE
		);
	}

	public SessionSnapshot {
		if (!worldLoaded) {
			playerLifecycleState = PlayerLifecycleState.UNAVAILABLE;
		}
		else if (playerLifecycleState == null || playerLifecycleState == PlayerLifecycleState.UNAVAILABLE) {
			playerLifecycleState = PlayerLifecycleState.ALIVE;
		}
	}

	public static SessionSnapshot initial() {
		return new SessionSnapshot(SessionMode.OUT_OF_WORLD, false, false, null, false, 0, 0L, PlayerLifecycleState.UNAVAILABLE);
	}

	public boolean companionActuationAllowed() {
		return playerLifecycleState == PlayerLifecycleState.ALIVE
			&& (mode == SessionMode.SINGLEPLAYER_LAN_HOST || mode == SessionMode.REMOTE_MULTIPLAYER);
	}

	public boolean requiresRespawn() {
		return playerLifecycleState == PlayerLifecycleState.DEAD;
	}

	public SessionSnapshot withMode(SessionMode value) {
		return new SessionSnapshot(value, clientBooted, worldLoaded, dimensionId, lanPublished, lanPort, tickCount, playerLifecycleState);
	}

	public SessionSnapshot withClientBooted(boolean value) {
		return new SessionSnapshot(mode, value, worldLoaded, dimensionId, lanPublished, lanPort, tickCount, playerLifecycleState);
	}

	public SessionSnapshot withWorldLoaded(boolean value) {
		return new SessionSnapshot(
			mode,
			clientBooted,
			value,
			dimensionId,
			lanPublished,
			lanPort,
			tickCount,
			value
				? (playerLifecycleState == PlayerLifecycleState.UNAVAILABLE ? PlayerLifecycleState.ALIVE : playerLifecycleState)
				: PlayerLifecycleState.UNAVAILABLE
		);
	}

	public SessionSnapshot withDimensionId(String value) {
		return new SessionSnapshot(mode, clientBooted, worldLoaded, value, lanPublished, lanPort, tickCount, playerLifecycleState);
	}

	public SessionSnapshot withLanPublished(boolean value) {
		return new SessionSnapshot(mode, clientBooted, worldLoaded, dimensionId, value, lanPort, tickCount, playerLifecycleState);
	}

	public SessionSnapshot withLanPort(int value) {
		return new SessionSnapshot(mode, clientBooted, worldLoaded, dimensionId, lanPublished, value, tickCount, playerLifecycleState);
	}

	public SessionSnapshot withTickCount(long value) {
		return new SessionSnapshot(mode, clientBooted, worldLoaded, dimensionId, lanPublished, lanPort, value, playerLifecycleState);
	}

	public SessionSnapshot withPlayerLifecycleState(PlayerLifecycleState value) {
		return new SessionSnapshot(mode, clientBooted, worldLoaded, dimensionId, lanPublished, lanPort, tickCount, value);
	}
}
