package ai.moeru.airicraft.agent.verification.scenarios;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.verification.VerificationPlayerProbe;
import ai.moeru.airicraft.agent.verification.VerificationRunner;
import ai.moeru.airicraft.agent.verification.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageFallContextVerificationTest {
	@Test
	void scenarioPassesWhenFallDamageUpdatesContextExcerpt() {
		AtomicReference<VerificationPlayerProbe> player = new AtomicReference<>(probe(4.0D, 64.0D, 20.0F, true));
		AtomicReference<List<String>> contextExcerpt = new AtomicReference<>(List.of("Context update: The current time is morning."));
		AtomicReference<List<SemanticEvent>> recentEvents = new AtomicReference<>(List.of());
		AtomicLong latestEventSeqNo = new AtomicLong(20L);
		AtomicBoolean survivalSet = new AtomicBoolean(false);
		AtomicBoolean controlledFallPrepared = new AtomicBoolean(false);
		AtomicReference<double[]> launchVelocity = new AtomicReference<>();

		VerificationRunner runner = new VerificationRunner();
		runner.register(new DamageFallContextVerification(
			() -> true,
			player::get,
			() -> survivalSet.set(true),
			() -> controlledFallPrepared.set(true),
			(x, y, z) -> launchVelocity.set(new double[]{x, y, z}),
			latestEventSeqNo::get,
			sinceSeqNo -> new SemanticEventQueryResult(sinceSeqNo + 1L, latestEventSeqNo.get(), false, recentEvents.get()),
			contextExcerpt::get
		));

		assertTrue(runner.start("damage.fall_context"));

		runner.onTick();
		runner.onTick();
		runner.onTick();
		runner.onTick();
		runner.onTick();
		runner.onTick();
		assertTrue(survivalSet.get());
		assertTrue(controlledFallPrepared.get());
		assertEquals(0.0D, launchVelocity.get()[0]);
		assertEquals(3.0D, launchVelocity.get()[1]);
		assertEquals(0.0D, launchVelocity.get()[2]);

		player.set(probe(4.0D, 80.0D, 20.0F, false));
		runner.onTick();

		player.set(probe(4.0D, 64.0D, 20.0F, true));
		runner.onTick();

		player.set(probe(4.0D, 64.0D, 16.0F, true));
		runner.onTick();

		latestEventSeqNo.set(21L);
		recentEvents.set(List.of(new SemanticEvent(
			21L,
			300L,
			10_000L,
			"combat.damage_taken",
			java.util.Map.of("actor", "self", "amount", 4.0F, "healthAfter", 16.0F)
		)));
		contextExcerpt.set(List.of("Context update: You took 4 damage from minecraft:fall and dropped to 16 health just now."));
		runner.onTick();
		runner.onTick();

		assertEquals(VerificationStatus.PASSED, runner.report().status());
		assertEquals("damage.fall_context", runner.report().scenarioName());
		assertEquals(10, runner.report().steps().size());
	}

	@Test
	void failureReportIncludesPlayerContextAndRecentEventDiagnostics() {
		AtomicReference<VerificationPlayerProbe> player = new AtomicReference<>(probe(2.0D, 64.0D, 20.0F, true));
		AtomicReference<List<String>> contextExcerpt = new AtomicReference<>(List.of("Context update: The weather is clear."));
		AtomicReference<List<SemanticEvent>> recentEvents = new AtomicReference<>(List.of(new SemanticEvent(
			8L,
			120L,
			5_000L,
			"combat.damage_taken",
			java.util.Map.of("actor", "self", "amount", 2.0F, "healthAfter", 18.0F)
		)));
		AtomicLong latestEventSeqNo = new AtomicLong(8L);

		VerificationRunner runner = new VerificationRunner();
		runner.register(new DamageFallContextVerification(
			() -> true,
			player::get,
			() -> {
			},
			() -> {
			},
			(x, y, z) -> {
			},
			latestEventSeqNo::get,
			sinceSeqNo -> new SemanticEventQueryResult(sinceSeqNo + 1L, latestEventSeqNo.get(), false, recentEvents.get()),
			contextExcerpt::get
		));

		assertTrue(runner.start("damage.fall_context"));

		runner.onTick();
		runner.onTick();
		runner.onTick();
		runner.onTick();
		runner.onTick();
		runner.onTick();

		player.set(probe(2.0D, 80.0D, 20.0F, false));
		runner.onTick();

		player.set(probe(2.0D, 64.0D, 20.0F, true));
		runner.onTick();

		player.set(probe(2.0D, 64.0D, 18.0F, true));
		runner.onTick();

		for (int index = 0; index <= 300; index++) {
			runner.onTick();
		}

		assertEquals(VerificationStatus.FAILED, runner.report().status());
		assertEquals("Timed out after 300 ticks", runner.report().message());
		assertEquals("Timed out after 300 ticks", runner.report().diagnostics().get("failureReason"));
		assertTrue(runner.report().diagnostics().containsKey("player"));
		assertTrue(runner.report().diagnostics().containsKey("contextExcerpt"));
		assertTrue(runner.report().diagnostics().containsKey("recentEvents"));
	}

	private static VerificationPlayerProbe probe(double x, double y, float health, boolean onGround) {
		return new VerificationPlayerProbe(
			x,
			y,
			4.0D,
			health,
			20.0F,
			20,
			5.0F,
			onGround,
			onGround ? 0.0D : 12.0D,
			"survival",
			"minecraft:overworld"
		);
	}
}
