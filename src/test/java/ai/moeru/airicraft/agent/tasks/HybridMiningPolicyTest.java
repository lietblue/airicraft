package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridMiningPolicyTest {
	@Test
	void localSourceProbeRunsOncePerSecondOfActiveExecution() {
		assertFalse(HybridMiningPolicy.shouldProbeLocalSources(0L));
		assertFalse(HybridMiningPolicy.shouldProbeLocalSources(19L));
		assertTrue(HybridMiningPolicy.shouldProbeLocalSources(20L));
		assertFalse(HybridMiningPolicy.shouldProbeLocalSources(21L));
		assertTrue(HybridMiningPolicy.shouldProbeLocalSources(40L));
	}

	@Test
	void localDryExhaustionRequiresNoDrySourceAndAnUnderwaterSource() {
		assertFalse(HybridMiningPolicy.localDryExhaustionFallbackDue(true, true));
		assertFalse(HybridMiningPolicy.localDryExhaustionFallbackDue(false, false));
		assertTrue(HybridMiningPolicy.localDryExhaustionFallbackDue(false, true));
	}
}
