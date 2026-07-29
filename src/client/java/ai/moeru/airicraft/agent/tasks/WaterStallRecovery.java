package ai.moeru.airicraft.agent.tasks;

final class WaterStallRecovery {
	static final long STALL_TICKS = 60L;
	static final double STALL_PROGRESS_DISTANCE = 0.75D;
	static final double RECOVERED_DISTANCE = 2.0D;

	private Sample stallAnchor;
	private long stallAnchorTick = -1L;
	private Sample recoveryAnchor;
	private boolean recovering;

	Decision observe(long tick, Sample sample, boolean pathingActive) {
		if (sample == null) {
			return Decision.NONE;
		}
		if (recovering) {
			if (!sample.touchingWater() || recoveryAnchor == null || recoveryAnchor.distanceTo(sample) >= RECOVERED_DISTANCE) {
				clear();
				return Decision.RESTORE;
			}
			return Decision.NONE;
		}
		if (!pathingActive) {
			clearTracking();
			return Decision.NONE;
		}
		if (!sample.touchingWater()) {
			clearTracking();
			return Decision.NONE;
		}
		if (stallAnchor == null
			|| tick < stallAnchorTick
			|| stallAnchor.distanceTo(sample) >= STALL_PROGRESS_DISTANCE) {
			stallAnchor = sample;
			stallAnchorTick = tick;
			return Decision.NONE;
		}
		if (tick - stallAnchorTick < STALL_TICKS) {
			return Decision.NONE;
		}
		recovering = true;
		recoveryAnchor = sample;
		stallAnchor = sample;
		stallAnchorTick = tick;
		return Decision.RAISE_AND_REPLAN;
	}

	boolean clear() {
		boolean wasRecovering = recovering;
		recovering = false;
		recoveryAnchor = null;
		clearTracking();
		return wasRecovering;
	}

	private void clearTracking() {
		stallAnchor = null;
		stallAnchorTick = -1L;
	}

	enum Decision {
		NONE,
		RAISE_AND_REPLAN,
		RESTORE
	}

	record Sample(boolean touchingWater, double x, double y, double z) {
		double distanceTo(Sample other) {
			double dx = x - other.x;
			double dy = y - other.y;
			double dz = z - other.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}
}
