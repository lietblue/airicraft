package ai.moeru.airicraft.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooperativeActionGraphScenarioTest {
	@Test
	void breadScenarioExercisesSuspensionForegroundWorkAndAutomaticResumption() throws Exception {
		Path config = repositoryRoot().resolve("scenarios/bread-cooperative-watch/scenario.yml");
		EvaluationScenario scenario = EvaluationScenarioLoader.load(config);
		Path archive = config.getParent().resolve(scenario.worldArchive()).normalize();

		assertEquals("bread-cooperative-watch", scenario.id());
		assertTrue(Files.isRegularFile(archive));
		assertTrue(scenario.prompt().contains("bread goal suspends"));
		assertTrue(scenario.prompt().contains("dirt goal finish in the foreground"));
		assertTrue(scenario.prompt().contains("resume automatically"));
		assertEquals(2, scenario.checks().size());
	}

	private static Path repositoryRoot() {
		Path candidate = Path.of("").toAbsolutePath();
		while (candidate != null && !Files.isDirectory(candidate.resolve("scenarios"))) {
			candidate = candidate.getParent();
		}
		if (candidate == null) {
			throw new IllegalStateException("repository scenarios directory not found");
		}
		return candidate;
	}
}
