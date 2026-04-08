package ai.moeru.airicraft.wrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiricraftCliMainTest {
	@Test
	void statusRendersDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.statusPayload = linkedMap(
			"available", false,
			"bridgeAvailable", false,
			"worldLoaded", false,
			"sessionState", "minecraft_unavailable",
			"state", "minecraft_unavailable",
			"message", "Minecraft bridge is not active"
		);

		CliResult result = execute(transport, "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().startsWith(
			"status: ok\n" +
				"command: status\n" +
				"available: false\n"
		));
		assertTrue(result.output().contains("state: minecraft_unavailable\n"));
	}

	@Test
	void agentContextRendersCompactionState() {
		TestTransport transport = new TestTransport();
		transport.agentContextPayload = linkedMap(
			"available", true,
			"planner", linkedMap(
				"configured", true,
				"plannerVisionMode", "native_tool_image",
				"inFlight", false,
				"plannerInFlight", false,
				"compactionInFlight", false,
				"captureInFlight", false,
				"toolInFlight", false,
				"toolUsed", false,
				"context", linkedMap(
					"compactionTriggerTokens", 65536,
					"compactionPending", true,
					"rawArchiveEntryCount", 12,
					"canonicalMessageCount", 8,
					"pendingEntryCount", 1,
					"frozenPlannerMessageCount", 0,
					"lastObservedEventSeqNo", 42,
					"lastTimeBeaconAtMs", 123456789L
				)
			)
		);

		CliResult result = execute(transport, "agent", "context");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent context\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
		assertTrue(result.output().contains("compactionPending: true\n"));
		assertTrue(result.output().contains("canonicalMessageCount: 8\n"));
	}

	@Test
	void agentSessionOpenLanRendersDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.agentSessionOpenLanPayload = linkedMap(
			"opened", true,
			"port", 25565
		);

		CliResult result = execute(transport, "agent", "session", "open-lan");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().startsWith(
			"status: ok\n" +
				"command: agent session open-lan\n"
		));
		assertTrue(result.output().contains("opened: true\n"));
		assertTrue(result.output().contains("port: 25565\n"));
	}

	@Test
	void agentDebugChatPassesMessagePayload() {
		TestTransport transport = new TestTransport();
		transport.agentDebugChatPayload = linkedMap(
			"available", true,
			"accepted", true,
			"senderName", "Player688",
			"message", "@agent get me 4 wood logs"
		);

		CliResult result = execute(
			transport,
			"agent", "debug", "chat",
			"--message", "@agent get me 4 wood logs"
		);

		assertEquals(0, result.exitCode());
		assertEquals("@agent get me 4 wood logs", transport.lastDebugChatMessage);
		assertTrue(result.output().contains("command: agent debug chat\n"));
		assertTrue(result.output().contains("accepted: true\n"));
	}

	@Test
	void agentStatusIncludesPlannerVisionMode() {
		TestTransport transport = new TestTransport();
		transport.agentStatusPayload = linkedMap(
			"available", true,
			"initialized", true,
			"tickCount", 42,
			"llmAvailable", true,
			"visionAvailable", false,
			"plannerVisionMode", "native_tool_image",
			"degraded", false
		);

		CliResult result = execute(transport, "agent", "status");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent status\n"));
		assertTrue(result.output().contains("plannerVisionMode: native_tool_image\n"));
	}

	@Test
	void agentTasksShowsCurrentTaskSnapshot() {
		TestTransport transport = new TestTransport();
		transport.agentTasksPayload = linkedMap(
			"available", true,
			"task", linkedMap(
				"state", "RUNNING",
				"spec", linkedMap(
					"type", "COLLECT_RESOURCE",
					"resourceKind", "WOOD_LOGS",
					"quantity", 4
				)
			)
		);

		CliResult result = execute(transport, "agent", "tasks");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent tasks\n"));
		assertTrue(result.output().contains("state: RUNNING\n"));
		assertTrue(result.output().contains("resourceKind: WOOD_LOGS\n"));
	}

	@Test
	void agentTasksSubmitPassesNormalizedTaskPayload() {
		TestTransport transport = new TestTransport();
		transport.agentTaskSubmitPayload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);

		CliResult result = execute(
			transport,
			"agent", "tasks", "submit",
			"--type", "collect-resource",
			"--resource", "wood-logs",
			"--quantity", "4"
		);

		assertEquals(0, result.exitCode());
		assertEquals("COLLECT_RESOURCE", transport.lastSubmittedTask.get("type"));
		assertEquals("WOOD_LOGS", transport.lastSubmittedTask.get("resourceKind"));
		assertEquals(4, transport.lastSubmittedTask.get("quantity"));
		assertTrue(result.output().contains("command: agent tasks submit\n"));
	}

	@Test
	void agentLedgerRendersCurrentMissionLedger() {
		TestTransport transport = new TestTransport();
		transport.agentLedgerPayload = linkedMap(
			"available", true,
			"ledger", linkedMap(
				"missionId", "mission-wood-1",
				"activeStepId", "collect_logs"
			)
		);

		CliResult result = execute(transport, "agent", "ledger");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent ledger\n"));
		assertTrue(result.output().contains("missionId: mission-wood-1\n"));
		assertTrue(result.output().contains("activeStepId: collect_logs\n"));
	}

	@Test
	void agentEvidenceRendersWorldEvidenceSummary() {
		TestTransport transport = new TestTransport();
		transport.agentEvidencePayload = linkedMap(
			"available", true,
			"evidence", linkedMap(
				"dimension", "minecraft:overworld",
				"inventoryCounts", linkedMap(
					"WOOD_LOGS", 4
				)
			)
		);

		CliResult result = execute(transport, "agent", "evidence");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent evidence\n"));
		assertTrue(result.output().contains("dimension: minecraft:overworld\n"));
		assertTrue(result.output().contains("WOOD_LOGS: 4\n"));
	}

	@Test
	void agentStepExecutionRendersLatestStepResult() {
		TestTransport transport = new TestTransport();
		transport.agentStepExecutionPayload = linkedMap(
			"available", true,
			"stepExecution", linkedMap(
				"stepId", "craft_sticks",
				"status", "RUNNING"
			)
		);

		CliResult result = execute(transport, "agent", "step-execution");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("command: agent step-execution\n"));
		assertTrue(result.output().contains("stepId: craft_sticks\n"));
		assertTrue(result.output().contains("status: RUNNING\n"));
	}

	@Test
	void agentMissionSubmitPassesNormalizedMissionPayload() {
		TestTransport transport = new TestTransport();
		transport.agentMissionSubmitPayload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);

		CliResult result = execute(
			transport,
			"agent", "mission", "submit",
			"--type", "collect-resource",
			"--resource", "wood-logs",
			"--quantity", "4"
		);

		assertEquals(0, result.exitCode());
		assertEquals("COLLECT_RESOURCE", transport.lastSubmittedMission.get("type"));
		assertEquals("WOOD_LOGS", transport.lastSubmittedMission.get("resourceKind"));
		assertEquals(4, transport.lastSubmittedMission.get("quantity"));
		assertTrue(result.output().contains("command: agent mission submit\n"));
	}

	@Test
	void agentMissionSubmitReadsLedgerPayloadFromFile(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.agentMissionSubmitPayload = linkedMap(
			"available", true,
			"task", linkedMap("state", "QUEUED")
		);
		Path ledgerFile = tempDir.resolve("ledger.json");
		Files.writeString(
			ledgerFile,
			"""
				{
				  "missionId": "mission-craft-1",
				  "missionType": "CRAFT_TOOL",
				  "goalText": "Turn wood into sticks",
				  "steps": [],
				  "activeStepId": null,
				  "completionCriteria": [],
				  "replanReason": "debug",
				  "plannerNotes": "manual"
				}
				""",
			StandardCharsets.UTF_8
		);

		CliResult result = execute(
			transport,
			"agent", "mission", "submit",
			"--ledger-file", ledgerFile.toString()
		);

		assertEquals(0, result.exitCode());
		assertEquals("mission-craft-1", transport.lastSubmittedMission.get("missionId"));
		assertEquals("CRAFT_TOOL", transport.lastSubmittedMission.get("missionType"));
		assertTrue(result.output().contains("command: agent mission submit\n"));
	}

	@Test
	void agentCompactPassesWaitAndTimeout() {
		TestTransport transport = new TestTransport();
		transport.agentCompactPayload = linkedMap(
			"available", true,
			"started", true,
			"completed", false,
			"timeoutMs", 7000,
			"planner", linkedMap(
				"configured", true,
				"plannerVisionMode", "external_summary",
				"inFlight", true,
				"plannerInFlight", false,
				"compactionInFlight", true,
				"captureInFlight", false,
				"toolInFlight", false,
				"context", linkedMap(
					"compactionPending", false,
					"canonicalMessageCount", 5,
					"rawArchiveEntryCount", 9
				)
			)
		);

		CliResult result = execute(transport, "agent", "compact", "--no-wait", "--timeout-seconds", "7");

		assertEquals(0, result.exitCode());
		assertFalse(transport.lastCompactWait);
		assertEquals(Integer.valueOf(7000), transport.lastCompactTimeoutMs);
		assertTrue(result.output().contains("command: agent compact\n"));
		assertTrue(result.output().contains("completed: false\n"));
	}

	@Test
	void agentEventsRecentPassesSinceFilter() {
		TestTransport transport = new TestTransport();
		transport.agentEventsPayload = linkedMap(
			"available", true,
			"oldestSeqNo", 10,
			"latestSeqNo", 12,
			"truncated", false,
			"events", List.of(linkedMap(
				"seqNo", 12,
				"tick", 300,
				"timestampMs", 123456789L,
				"type", "planner.goal_set"
			))
		);

		CliResult result = execute(transport, "agent", "events", "recent", "--since", "11");

		assertEquals(0, result.exitCode());
		assertEquals(Long.valueOf(11L), transport.lastEventSince);
		assertTrue(result.output().contains("eventCount: 1\n"));
	}

	@Test
	void worldsListOmitsVerboseFieldsByDefault() {
		TestTransport transport = new TestTransport();
		transport.worldsPayload = linkedMap(
			"available", true,
			"sessionState", "out_of_world",
			"worlds", List.of(linkedMap(
				"worldId", "survival-12345678",
				"name", "survival",
				"displayName", "Survival",
				"lastPlayed", 123L,
				"gameMode", "survival",
				"selectable", true,
				"immediatelyLoadable", true,
				"locked", false,
				"unavailable", false,
				"experimental", false,
				"details", "A long details string",
				"version", "1.21.8"
			))
		);

		CliResult compact = execute(transport, "worlds", "list");
		CliResult verbose = execute(transport, "worlds", "list", "--verbose");

		assertEquals(0, compact.exitCode());
		assertTrue(compact.output().contains("worldCount: 1\n"));
		assertFalse(compact.output().contains("details:"));
		assertTrue(verbose.output().contains("details: A long details string\n"));
		assertTrue(verbose.output().contains("version: 1.21.8\n"));
	}

	@Test
	void missingRequiredArgumentReturnsUsageError() {
		CliResult result = execute(new TestTransport(), "worlds", "join");

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("status: error\n"));
		assertTrue(result.output().contains("command: worlds join\n"));
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(result.output().contains("Missing required option: '--world-id"));
	}

	@Test
	void bridgeErrorsMapToExitCodes() {
		TestTransport transport = new TestTransport();
		transport.worldsJoinFailure = new BridgeUnavailableException("already_in_world", "A world is already loaded");
		transport.serversListFailure = new BridgeUnavailableException("minecraft_unavailable", "Minecraft bridge is not active");

		CliResult domainFailure = execute(transport, "worlds", "join", "--world-id", "survival-12345678");
		CliResult transportFailure = execute(transport, "servers", "list");

		assertEquals(4, domainFailure.exitCode());
		assertTrue(domainFailure.output().contains("error_code: already_in_world\n"));
		assertEquals(3, transportFailure.exitCode());
		assertTrue(transportFailure.output().contains("error_code: minecraft_unavailable\n"));
	}

	@Test
	void helpCommandShowsScopedUsage() {
		CliResult result = execute(new TestTransport(), "help", "highlights", "block");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("Usage: airicraft highlights block"));
		assertTrue(result.output().contains("--x"));
		assertTrue(result.output().contains("--duration-seconds"));
	}

	@Test
	void cameraScreenshotWritesFileAndPrintsMetadata(@TempDir Path tempDir) throws Exception {
		TestTransport transport = new TestTransport();
		transport.capturedImage = new CapturedImage(
			new byte[]{1, 2, 3, 4},
			"png",
			854,
			480,
			1920,
			1080,
			123456789L
		);
		Path output = tempDir.resolve("captures/view.png");

		CliResult result = execute(transport, "camera", "screenshot", "--output", output.toString());

		assertEquals(0, result.exitCode());
		assertTrue(Files.exists(output));
		assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(output));
		assertTrue(result.output().contains("status: ok\n"));
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("outputPath: " + output.toAbsolutePath().normalize() + "\n"));
		assertTrue(result.output().contains("format: png\n"));
		assertTrue(result.output().contains("width: 854\n"));
		assertTrue(result.output().contains("height: 480\n"));
		assertTrue(result.output().contains("capturedAtMs: 123456789\n"));
	}

	@Test
	void cameraScreenshotRequiresOutputPath() {
		CliResult result = execute(new TestTransport(), "camera", "screenshot");

		assertEquals(2, result.exitCode());
		assertTrue(result.output().contains("status: error\n"));
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("error_code: invalid_arguments\n"));
		assertTrue(result.output().contains("Missing required option: '--output"));
	}

	@Test
	void cameraScreenshotBridgeErrorsUseTransportExitCodes() {
		TestTransport transport = new TestTransport();
		transport.captureScreenshotFailure = new BridgeUnavailableException("capture_timeout", "Screenshot capture timed out");

		CliResult result = execute(transport, "camera", "screenshot", "--output", "capture.png");

		assertEquals(4, result.exitCode());
		assertTrue(result.output().contains("command: camera screenshot\n"));
		assertTrue(result.output().contains("error_code: capture_timeout\n"));
	}

	@Test
	void visionDescribePrintsDeterministicText() {
		TestTransport transport = new TestTransport();
		transport.visionDescriptionResult = new VisionDescriptionResult(
			"text",
			987654321L,
			"gpt-4.1-mini",
			"A birch forest hill with open sky and no visible structures."
		);

		CliResult result = execute(transport, "vision", "describe");

		assertEquals(0, result.exitCode());
		assertTrue(result.output().contains("status: ok\n"));
		assertTrue(result.output().contains("command: vision describe\n"));
		assertTrue(result.output().contains("format: text\n"));
		assertTrue(result.output().contains("capturedAtMs: 987654321\n"));
		assertTrue(result.output().contains("model: gpt-4.1-mini\n"));
		assertTrue(result.output().contains("description: A birch forest hill with open sky and no visible structures.\n"));
	}

	@Test
	void visionDescribePassesPromptOverride() {
		TestTransport transport = new TestTransport();

		CliResult result = execute(transport, "vision", "describe", "--prompt", "Describe hazards only.");

		assertEquals(0, result.exitCode());
		assertEquals("Describe hazards only.", transport.lastVisionPrompt);
	}

	private static CliResult execute(MinecraftTransport transport, String... args) {
		StringWriter writer = new StringWriter();
		CommandLine commandLine = AiricraftCliMain.createCommandLine(transport, new PrintWriter(writer, true));
		int exitCode = commandLine.execute(args);
		return new CliResult(exitCode, writer.toString().replace("\r\n", "\n"));
	}

	private static LinkedHashMap<String, Object> linkedMap(Object... values) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			map.put(String.valueOf(values[index]), values[index + 1]);
		}
		return map;
	}

	private record CliResult(int exitCode, String output) {
	}

	private static final class TestTransport implements MinecraftTransport {
		private Map<String, Object> statusPayload = Map.of();
		private Map<String, Object> worldsPayload = Map.of("worlds", List.of());
		private Map<String, Object> serversPayload = Map.of("servers", List.of());
		private Map<String, Object> focusPayload = Map.of();
		private Map<String, Object> snapshotPayload = Map.of();
		private Map<String, Object> agentStatusPayload = Map.of();
		private Map<String, Object> agentSessionPayload = Map.of();
		private Map<String, Object> agentSessionOpenLanPayload = Map.of();
		private Map<String, Object> agentGoalsPayload = Map.of();
		private Map<String, Object> agentTreePayload = Map.of();
		private Map<String, Object> agentDialoguePayload = Map.of();
		private Map<String, Object> agentDebugChatPayload = Map.of();
		private Map<String, Object> agentContextPayload = Map.of();
		private Map<String, Object> agentTasksPayload = Map.of();
		private Map<String, Object> agentLedgerPayload = Map.of();
		private Map<String, Object> agentEvidencePayload = Map.of();
		private Map<String, Object> agentStepExecutionPayload = Map.of();
		private Map<String, Object> agentTaskSubmitPayload = Map.of();
		private Map<String, Object> agentMissionSubmitPayload = Map.of();
		private Map<String, Object> agentEventsPayload = Map.of("events", List.of());
		private Map<String, Object> agentCompactPayload = Map.of("started", true);
		private Map<String, Object> blockHighlightPayload = Map.of("highlightId", "highlight-1");
		private Map<String, Object> regionHighlightPayload = Map.of("highlightId", "highlight-2");
		private Map<String, Object> highlightsPayload = Map.of("highlights", List.of());
		private Map<String, Object> clearHighlightPayload = Map.of("cleared", true);
		private Map<String, Object> clearHighlightsPayload = Map.of("cleared", true, "clearedCount", 0);
		private Map<String, Object> worldsJoinPayload = Map.of("started", true);
		private Map<String, Object> serversJoinPayload = Map.of("started", true);
		private Map<String, Object> lookAtPayload = Map.of("started", true);
		private CapturedImage capturedImage = new CapturedImage(new byte[0], "png", 854, 480, 854, 480, 1L);
		private VisionDescriptionResult visionDescriptionResult = new VisionDescriptionResult("text", 1L, "gpt-4.1-mini", "desc");
		private String lastVisionPrompt;
		private Long lastEventSince;
		private boolean lastCompactWait = true;
		private Integer lastCompactTimeoutMs;
		private Map<String, Object> lastSubmittedTask;
		private Map<String, Object> lastSubmittedMission;
		private String lastDebugChatMessage;

		private RuntimeException worldsJoinFailure;
		private RuntimeException serversListFailure;
		private RuntimeException captureScreenshotFailure;

		@Override
		public Map<String, Object> getStatus() {
			return statusPayload;
		}

		@Override
		public Map<String, Object> getFocus() {
			return focusPayload;
		}

		@Override
		public Map<String, Object> getWorldSnapshot(Integer x, Integer y, Integer z, int radius) {
			return snapshotPayload;
		}

		@Override
		public CapturedImage captureScreenshot() {
			if (captureScreenshotFailure != null) {
				throw captureScreenshotFailure;
			}
			return capturedImage;
		}

		@Override
		public VisionDescriptionResult describeVision(String prompt) {
			lastVisionPrompt = prompt;
			return visionDescriptionResult;
		}

		@Override
		public Map<String, Object> listWorlds() {
			return worldsPayload;
		}

		@Override
		public Map<String, Object> joinWorld(String worldId) {
			if (worldsJoinFailure != null) {
				throw worldsJoinFailure;
			}
			return worldsJoinPayload;
		}

		@Override
		public Map<String, Object> listServers() {
			if (serversListFailure != null) {
				throw serversListFailure;
			}
			return serversPayload;
		}

		@Override
		public Map<String, Object> joinServer(String serverId) {
			return serversJoinPayload;
		}

		@Override
		public Map<String, Object> lookAt(double x, double y, double z) {
			return lookAtPayload;
		}

		@Override
		public Map<String, Object> createBlockHighlight(int x, int y, int z, String color, Long durationMs, String overlayText) {
			return blockHighlightPayload;
		}

		@Override
		public Map<String, Object> createRegionHighlight(int x1, int y1, int z1, int x2, int y2, int z2, String color, Long durationMs, String overlayText) {
			return regionHighlightPayload;
		}

		@Override
		public Map<String, Object> listHighlights() {
			return highlightsPayload;
		}

		@Override
		public Map<String, Object> clearHighlight(String highlightId) {
			return clearHighlightPayload;
		}

		@Override
		public Map<String, Object> clearHighlights() {
			return clearHighlightsPayload;
		}

		@Override
		public Map<String, Object> getAgentStatus() {
			return agentStatusPayload;
		}

		@Override
		public Map<String, Object> getAgentSession() {
			return agentSessionPayload;
		}

		@Override
		public Map<String, Object> openAgentSessionLan() {
			return agentSessionOpenLanPayload;
		}

		@Override
		public Map<String, Object> getAgentGoals() {
			return agentGoalsPayload;
		}

		@Override
		public Map<String, Object> getAgentTree() {
			return agentTreePayload;
		}

		@Override
		public Map<String, Object> getAgentDialogue() {
			return agentDialoguePayload;
		}

		@Override
		public Map<String, Object> sendAgentDebugChat(String message) {
			lastDebugChatMessage = message;
			return agentDebugChatPayload;
		}

		@Override
		public Map<String, Object> getAgentContext() {
			return agentContextPayload;
		}

		@Override
		public Map<String, Object> getAgentTasks() {
			return agentTasksPayload;
		}

		@Override
		public Map<String, Object> submitAgentTask(Map<String, Object> taskPayload) {
			lastSubmittedTask = taskPayload;
			return agentTaskSubmitPayload;
		}

		@Override
		public Map<String, Object> getAgentLedger() {
			return agentLedgerPayload;
		}

		@Override
		public Map<String, Object> getAgentEvidence() {
			return agentEvidencePayload;
		}

		@Override
		public Map<String, Object> getAgentStepExecution() {
			return agentStepExecutionPayload;
		}

		@Override
		public Map<String, Object> submitAgentMission(Map<String, Object> missionPayload) {
			lastSubmittedMission = missionPayload;
			return agentMissionSubmitPayload;
		}

		@Override
		public Map<String, Object> cancelAgentTask() {
			return Map.of("available", true, "cancelled", true);
		}

		@Override
		public Map<String, Object> listRecentAgentEvents(Long sinceSeqNo) {
			lastEventSince = sinceSeqNo;
			return agentEventsPayload;
		}

		@Override
		public Map<String, Object> triggerAgentCompaction(boolean wait, Integer timeoutMs) {
			lastCompactWait = wait;
			lastCompactTimeoutMs = timeoutMs;
			return agentCompactPayload;
		}
	}
}
