package ai.moeru.airicraft.agent.verification.scenarios;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.verification.ScenarioBuilder;
import ai.moeru.airicraft.agent.verification.VerificationPlayerProbe;
import ai.moeru.airicraft.agent.verification.VerificationScenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class DamageFallContextVerification extends VerificationScenario {
	private static final int PLAYER_ELEVATED_TIMEOUT_TICKS = 100;
	private static final int PLAYER_LANDED_TIMEOUT_TICKS = 400;
	private static final int HEALTH_DROP_TIMEOUT_TICKS = 200;
	private static final int DAMAGE_NOTICE_TIMEOUT_TICKS = 300;
	private static final double REQUIRED_RISE_BLOCKS = 6.0D;
	private static final double LAUNCH_VERTICAL_VELOCITY = 3.0D;

	private final BooleanSupplier verificationAvailable;
	private final Supplier<VerificationPlayerProbe> playerProbeSupplier;
	private final Runnable setSurvivalMode;
	private final Runnable prepareControlledFall;
	private final PlayerLauncher launcher;
	private final LongSupplier latestEventSeqNo;
	private final LongFunction<SemanticEventQueryResult> recentEventsSince;
	private final Supplier<List<String>> contextExcerptSupplier;

	private VerificationPlayerProbe baselinePlayer;
	private VerificationPlayerProbe latestPlayer;
	private List<SemanticEvent> latestRecentEvents = List.of();
	private List<String> baselineContextExcerpt = List.of();
	private List<String> latestContextExcerpt = List.of();
	private long baselineEventSeqNo;
	private String observationError;

	public DamageFallContextVerification(
		BooleanSupplier verificationAvailable,
		Supplier<VerificationPlayerProbe> playerProbeSupplier,
		Runnable setSurvivalMode,
		Runnable prepareControlledFall,
		PlayerLauncher launcher,
		LongSupplier latestEventSeqNo,
		LongFunction<SemanticEventQueryResult> recentEventsSince,
		Supplier<List<String>> contextExcerptSupplier
	) {
		this.verificationAvailable = Objects.requireNonNull(verificationAvailable, "verificationAvailable");
		this.playerProbeSupplier = Objects.requireNonNull(playerProbeSupplier, "playerProbeSupplier");
		this.setSurvivalMode = Objects.requireNonNull(setSurvivalMode, "setSurvivalMode");
		this.prepareControlledFall = Objects.requireNonNull(prepareControlledFall, "prepareControlledFall");
		this.launcher = Objects.requireNonNull(launcher, "launcher");
		this.latestEventSeqNo = Objects.requireNonNull(latestEventSeqNo, "latestEventSeqNo");
		this.recentEventsSince = Objects.requireNonNull(recentEventsSince, "recentEventsSince");
		this.contextExcerptSupplier = Objects.requireNonNull(contextExcerptSupplier, "contextExcerptSupplier");
	}

	@Override
	public String name() {
		return "damage.fall_context";
	}

	@Override
	protected void onStart() {
		baselinePlayer = null;
		latestPlayer = null;
		latestRecentEvents = List.of();
		baselineContextExcerpt = List.of();
		latestContextExcerpt = List.of();
		baselineEventSeqNo = 0L;
		observationError = null;
	}

	@Override
	protected void define(ScenarioBuilder builder) {
		builder
			.require("singleplayer local verification available", verificationAvailable)
			.require("player starts on ground", this::playerIsGrounded)
			.action("capture fall baseline", this::captureBaseline)
			.action("set player gamemode to survival", setSurvivalMode)
			.action("prepare controlled fall", prepareControlledFall)
			.action("launch player upward", this::launchBaselinePlayerUpward)
			.waitUntil("player reached elevated position", PLAYER_ELEVATED_TIMEOUT_TICKS, this::playerReachedElevatedPosition)
			.waitUntil("player landed again", PLAYER_LANDED_TIMEOUT_TICKS, this::playerLanded)
			.waitUntil("player health dropped", HEALTH_DROP_TIMEOUT_TICKS, this::playerHealthDropped)
			.waitUntil("planner context shows new damage notice", DAMAGE_NOTICE_TIMEOUT_TICKS, this::plannerContextShowsDamageNotice);
	}

	@Override
	protected Map<String, Object> diagnostics() {
		LinkedHashMap<String, Object> diagnostics = new LinkedHashMap<>();
		if (latestPlayer != null) {
			diagnostics.put("player", latestPlayer.asMap());
		}
		if (!latestContextExcerpt.isEmpty()) {
			diagnostics.put("contextExcerpt", latestContextExcerpt);
		}
		if (!latestRecentEvents.isEmpty()) {
			diagnostics.put("recentEvents", latestRecentEvents);
		}
		if (observationError != null && !observationError.isBlank()) {
			diagnostics.put("observationError", observationError);
		}
		return Map.copyOf(diagnostics);
	}

	private boolean playerIsGrounded() {
		VerificationPlayerProbe probe = refreshPlayerProbe();
		return probe != null && probe.onGround();
	}

	private void captureBaseline() {
		VerificationPlayerProbe probe = requireLatestPlayer();
		baselinePlayer = probe;
		baselineEventSeqNo = latestEventSeqNo.getAsLong();
		baselineContextExcerpt = List.copyOf(safeContextExcerpt());
		latestContextExcerpt = baselineContextExcerpt;
		latestRecentEvents = List.copyOf(recentEventsSince.apply(baselineEventSeqNo).events());
	}

	private void launchBaselinePlayerUpward() {
		if (baselinePlayer == null) {
			throw new IllegalStateException("Baseline player probe was not captured");
		}
		launcher.launch(0.0D, LAUNCH_VERTICAL_VELOCITY, 0.0D);
		refreshDiagnostics();
	}

	private boolean playerReachedElevatedPosition() {
		VerificationPlayerProbe probe = refreshDiagnostics();
		return baselinePlayer != null
			&& probe != null
			&& probe.y() >= baselinePlayer.y() + REQUIRED_RISE_BLOCKS;
	}

	private boolean playerLanded() {
		VerificationPlayerProbe probe = refreshDiagnostics();
		return baselinePlayer != null
			&& probe != null
			&& probe.onGround()
			&& probe.y() <= baselinePlayer.y() + 1.5D;
	}

	private boolean playerHealthDropped() {
		VerificationPlayerProbe probe = refreshDiagnostics();
		return baselinePlayer != null
			&& probe != null
			&& probe.health() < baselinePlayer.health();
	}

	private boolean plannerContextShowsDamageNotice() {
		refreshDiagnostics();
		if (latestContextExcerpt.isEmpty() || latestContextExcerpt.equals(baselineContextExcerpt)) {
			return false;
		}
		for (String line : latestContextExcerpt) {
			if (line != null && line.contains("You took") && line.contains("damage")) {
				return true;
			}
		}
		return false;
	}

	private VerificationPlayerProbe refreshDiagnostics() {
		VerificationPlayerProbe probe = refreshPlayerProbe();
		latestContextExcerpt = List.copyOf(safeContextExcerpt());
		if (baselinePlayer != null) {
			latestRecentEvents = List.copyOf(recentEventsSince.apply(baselineEventSeqNo).events());
		}
		return probe;
	}

	private VerificationPlayerProbe refreshPlayerProbe() {
		try {
			observationError = null;
			latestPlayer = playerProbeSupplier.get();
			return latestPlayer;
		}
		catch (RuntimeException exception) {
			observationError = exception.getMessage();
			return null;
		}
	}

	private List<String> safeContextExcerpt() {
		try {
			observationError = null;
			return List.copyOf(contextExcerptSupplier.get());
		}
		catch (RuntimeException exception) {
			observationError = exception.getMessage();
			return List.of();
		}
	}

	private VerificationPlayerProbe requireLatestPlayer() {
		VerificationPlayerProbe probe = refreshPlayerProbe();
		if (probe == null) {
			throw new IllegalStateException(observationError == null ? "Player probe unavailable" : observationError);
		}
		return probe;
	}

	@FunctionalInterface
	public interface PlayerLauncher {
		void launch(double velocityX, double velocityY, double velocityZ);
	}
}
