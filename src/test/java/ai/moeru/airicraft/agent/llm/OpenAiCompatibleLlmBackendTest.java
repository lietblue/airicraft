package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.FinishStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleLlmBackendTest {
	@Test
	void generateParsesPlannerResponseAndUsage() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent follow me", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Sure, I'll follow you.", result.payload().replyText());
			assertEquals("set_goal", result.payload().intent().type());
			assertEquals(Integer.valueOf(1234), result.usage().promptTokens());
			assertEquals(Integer.valueOf(56), result.usage().completionTokens());
			assertEquals(Integer.valueOf(1290), result.usage().totalTokens());

			String body = bodyRef.get();
			assertTrue(body.contains("\"role\":\"system\""));
			assertTrue(body.contains("Alice said just now"));
		}
	}

	@Test
	void generateParsesNavigateToPlannerPayload() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"Heading to the spot.\\",\\"intent\\":{\\"type\\":\\"set_goal\\",\\"goalType\\":\\"NAVIGATE_TO\\",\\"position\\":{\\"x\\":12,\\"y\\":64,\\"z\\":-8,\\"exactY\\":true},\\"targetPlayer\\":null},\\"toolRequest\\":null}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent go to 12 64 -8", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Heading to the spot.", result.payload().replyText());
			assertEquals("set_goal", result.payload().intent().type());
			assertEquals(GoalType.NAVIGATE_TO, result.payload().intent().goalType());
			assertEquals(new GoalPosition(12, 64, -8, true), result.payload().intent().position());
		}
	}

	@Test
	void generateParsesMineBlocksPlannerPayload() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"Mining oak logs.\\",\\"intent\\":{\\"type\\":\\"set_goal\\",\\"goalType\\":\\"MINE_BLOCKS\\",\\"mineSpec\\":{\\"blockIds\\":[\\"minecraft:oak_log\\"],\\"quantity\\":16},\\"targetPlayer\\":null},\\"toolRequest\\":null}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent mine 16 oak logs", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Mining oak logs.", result.payload().replyText());
			assertEquals("set_goal", result.payload().intent().type());
			assertEquals(GoalType.MINE_BLOCKS, result.payload().intent().goalType());
			assertEquals(new GoalMineSpec(List.of("minecraft:oak_log"), 16), result.payload().intent().mineSpec());
		}
	}

	@Test
	void generateParsesSubmitTaskPlannerPayload() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"On it.\\",\\"intent\\":{\\"type\\":\\"submit_task\\",\\"taskSpec\\":{\\"type\\":\\"COLLECT_RESOURCE\\",\\"resourceKind\\":\\"WOOD_LOGS\\",\\"quantity\\":16}},\\"toolRequest\\":null}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent get wood", LlmMessageKind.USER_TURN)
			)));

			assertEquals("On it.", result.payload().replyText());
			assertEquals("submit_task", result.payload().intent().type());
			assertEquals(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), result.payload().intent().taskSpec());
		}
	}

	@Test
	void generateParsesCancelTaskPlannerPayload() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"Stopping the task.\\",\\"intent\\":{\\"type\\":\\"cancel_task\\",\\"taskSpec\\":null},\\"toolRequest\\":null}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent stop the task", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Stopping the task.", result.payload().replyText());
			assertEquals("cancel_task", result.payload().intent().type());
			assertEquals(null, result.payload().intent().taskSpec());
		}
	}

	@Test
	void generateParsesMissionUpdatePlannerPayload() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"Starting the mission.\\",\\"intent\\":{\\"type\\":\\"mission_update\\",\\"taskLedger\\":{\\"missionId\\":\\"mission-wood-1\\",\\"missionType\\":\\"COLLECT_RESOURCE\\",\\"goalText\\":\\"Collect 4 wood logs\\",\\"steps\\":[{\\"id\\":\\"collect_logs\\",\\"kind\\":\\"COLLECT_RESOURCE\\",\\"args\\":{\\"collectResource\\":{\\"resourceKind\\":\\"WOOD_LOGS\\",\\"quantity\\":4,\\"deliveryPolicy\\":\\"KEEP\\"}},\\"dependsOn\\":[],\\"status\\":\\"ACTIVE\\",\\"expectedEvidence\\":[{\\"type\\":\\"INVENTORY_DELTA_AT_LEAST\\",\\"resourceKind\\":\\"WOOD_LOGS\\",\\"quantity\\":4}],\\"retryBudget\\":2,\\"notes\\":\\"Collect logs\\"},{\\"id\\":\\"finish\\",\\"kind\\":\\"FINISH\\",\\"args\\":{\\"finish\\":{\\"reason\\":\\"Mission complete\\"}},\\"dependsOn\\":[\\"collect_logs\\"],\\"status\\":\\"PENDING\\",\\"expectedEvidence\\":[{\\"type\\":\\"STEP_COMPLETED\\",\\"stepId\\":\\"collect_logs\\"}],\\"retryBudget\\":0,\\"notes\\":\\"Finish\\"}],\\"activeStepId\\":\\"collect_logs\\",\\"completionCriteria\\":[{\\"type\\":\\"INVENTORY_DELTA_AT_LEAST\\",\\"resourceKind\\":\\"WOOD_LOGS\\",\\"quantity\\":4}],\\"replanReason\\":\\"user_request\\",\\"plannerNotes\\":\\"Keep it simple\\"}},\\"toolRequest\\":null}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent get 4 wood logs", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Starting the mission.", result.payload().replyText());
			assertEquals("mission_update", result.payload().intent().type());
			assertEquals(new TaskLedger(
				"mission-wood-1",
				MissionType.COLLECT_RESOURCE,
				"Collect 4 wood logs",
				List.of(
					new LedgerStep(
						"collect_logs",
						LedgerStepKind.COLLECT_RESOURCE,
						new LedgerStepPayload(
							new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							null
						),
						List.of(),
						LedgerStepStatus.ACTIVE,
						List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
						2,
						"Collect logs"
					),
					new LedgerStep(
						"finish",
						LedgerStepKind.FINISH,
						new LedgerStepPayload(
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							null,
							new FinishStepArgs("Mission complete")
						),
						List.of("collect_logs"),
						LedgerStepStatus.PENDING,
						List.of(new EvidenceRequirement(EvidenceKind.STEP_COMPLETED, null, null, "collect_logs", null)),
						0,
						"Finish"
					)
				),
				"collect_logs",
				List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
				"user_request",
				"Keep it simple"
			), result.payload().intent().taskLedger());
		}
	}

	@Test
	void generateParsesCraftMissionLedgerWithItemEvidence() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"Crafting sticks next.\\",\\"intent\\":{\\"type\\":\\"mission_update\\",\\"taskLedger\\":{\\"missionId\\":\\"mission-craft-1\\",\\"missionType\\":\\"CRAFT_TOOL\\",\\"goalText\\":\\"Turn wood into sticks\\",\\"steps\\":[{\\"id\\":\\"craft_sticks\\",\\"kind\\":\\"CRAFT_RECIPE\\",\\"args\\":{\\"craftRecipe\\":{\\"recipeId\\":\\"minecraft:stick\\",\\"quantity\\":4}},\\"dependsOn\\":[],\\"status\\":\\"ACTIVE\\",\\"expectedEvidence\\":[{\\"type\\":\\"ITEM_DELTA_AT_LEAST\\",\\"itemId\\":\\"minecraft:stick\\",\\"quantity\\":4}],\\"retryBudget\\":1,\\"notes\\":\\"Craft sticks from planks\\"}],\\"activeStepId\\":\\"craft_sticks\\",\\"completionCriteria\\":[],\\"replanReason\\":\\"step_completed\\",\\"plannerNotes\\":\\"Use inventory crafting\\"}},\\"toolRequest\\":null}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 120,
			    "completion_tokens": 40,
			    "total_tokens": 160
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent make sticks", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Crafting sticks next.", result.payload().replyText());
			assertEquals("mission_update", result.payload().intent().type());
			assertEquals(new TaskLedger(
				"mission-craft-1",
				MissionType.CRAFT_TOOL,
				"Turn wood into sticks",
				List.of(
					new LedgerStep(
						"craft_sticks",
						LedgerStepKind.CRAFT_RECIPE,
						new LedgerStepPayload(
							null,
							null,
							null,
							null,
							new CraftRecipeStepArgs("minecraft:stick", 4),
							null,
							null,
							null,
							null,
							null,
							null,
							null
						),
						List.of(),
						LedgerStepStatus.ACTIVE,
						List.of(new EvidenceRequirement(EvidenceKind.ITEM_DELTA_AT_LEAST, null, 4, null, "minecraft:stick", null)),
						1,
						"Craft sticks from planks"
					)
				),
				"craft_sticks",
				List.of(),
				"step_completed",
				"Use inventory crafting"
			), result.payload().intent().taskLedger());
		}
	}

	@Test
	void generateIgnoresMalformedStructuredPayloads() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"Trying my best.\\",\\"intent\\":{\\"type\\":\\"set_goal\\",\\"goalType\\":\\"NAVIGATE_TO\\",\\"position\\":{\\"x\\":12,\\"z\\":-8,\\"exactY\\":true},\\"mineSpec\\":{\\"blockIds\\":null,\\"quantity\\":16},\\"targetPlayer\\":null},\\"toolRequest\\":null}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 1234,
			    "completion_tokens": 56,
			    "total_tokens": 1290
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent go to 12 64 -8", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Trying my best.", result.payload().replyText());
			assertEquals("set_goal", result.payload().intent().type());
			assertEquals(GoalType.NAVIGATE_TO, result.payload().intent().goalType());
			assertEquals(null, result.payload().intent().position());
			assertEquals(null, result.payload().intent().mineSpec());
		}
	}

	@Test
	void generateBuildsMultimodalPlannerRequestWhenImageAttached() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"high",
				true
			));

			backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.userWithImage(
					"Tool result for take_a_look: current first-person view attached.",
					LlmMessageKind.TOOL_RESULT,
					new LlmImageAttachment("image/png", new byte[]{1, 2, 3}, "high")
				)
			)));

			String body = bodyRef.get();
			assertTrue(body.contains("\"type\":\"image_url\""));
			assertTrue(body.contains("\"detail\":\"high\""));
			assertTrue(body.contains("data:image/png;base64,AQID"));
			assertTrue(body.contains("Tool result for take_a_look"));
		}
	}

	@Test
	void stripMarkdownCodeFencesRemovesJsonFences() {
		String fenced = "```json\n{\"replyText\": \"hi\"}\n```";
		assertEquals("{\"replyText\": \"hi\"}", OpenAiCompatibleLlmBackend.stripMarkdownCodeFences(fenced));
	}

	@Test
	void stripMarkdownCodeFencesPassesThroughPlainJson() {
		String plain = "{\"replyText\": \"hi\"}";
		assertEquals(plain, OpenAiCompatibleLlmBackend.stripMarkdownCodeFences(plain));
	}

	@Test
	void generateParsesMarkdownWrappedContent() throws Exception {
		String innerJson = "{\"replyText\":\"Hey!\",\"intent\":{\"type\":\"none\",\"taskLedger\":null},\"toolRequest\":null,\"eventPolicyChanges\":null}";
		String wrappedContent = "```json\\n" + innerJson.replace("\"", "\\\"") + "\\n```";
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "%s"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 100,
			    "completion_tokens": 50,
			    "total_tokens": 150
			  }
			}
			""".formatted(wrappedContent);
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent hi", LlmMessageKind.USER_TURN)
			)));

			assertEquals("Hey!", result.payload().replyText());
			assertEquals("none", result.payload().intent().type());
		}
	}

	@Test
	void generateTreatsPlainTextContentAsReplyOnly() throws Exception {
		String responseBody = """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "I'm ready and waiting for your next command!"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 100,
			    "completion_tokens": 13,
			    "total_tokens": 113
			  }
			}
			""";
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, responseBody)) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Alice said just now: @agent hello", LlmMessageKind.USER_TURN)
			)));

			assertEquals("I'm ready and waiting for your next command!", result.payload().replyText());
			assertEquals("reply_only", result.payload().intent().type());
		}
	}

	@Test
	void generateParsesEventPolicyChanges() throws Exception {
		AtomicReference<String> bodyRef = new AtomicReference<>();
		try (TestServer server = TestServer.start(bodyRef, """
			{
			  "choices": [
			    {
			      "message": {
			        "content": "{\\"replyText\\":\\"\\",\\"intent\\":{\\"type\\":\\"none\\",\\"goalType\\":null,\\"targetPlayer\\":null},\\"toolRequest\\":null,\\"eventPolicyChanges\\":{\\"clearAll\\":false,\\"removeRuleIds\\":[\\"old-rule\\"],\\"upserts\\":[{\\"ruleId\\":\\"mute-system\\",\\"effect\\":\\"ignore\\",\\"match\\":{\\"eventType\\":\\"social.system_message\\",\\"speaker\\":\\"server\\"},\\"reason\\":\\"Mute repeated system spam\\"}]}}"
			      }
			    }
			  ],
			  "usage": {
			    "prompt_tokens": 10,
			    "completion_tokens": 5,
			    "total_tokens": 15
			  }
			}
			""")) {
			OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(new AgentConfig.LlmConfig(
				"http://127.0.0.1:" + server.port(),
				"planner-key",
				"planner-model",
				"https://api.openai.com/v1",
				"",
				"",
				15_000,
				10_000,
				8,
				65_536,
				"low",
				false
			));

			LlmCallResult<PlannerResponse> result = backend.generate(LlmConversation.of(List.of(
				LlmChatMessage.system("system"),
				LlmChatMessage.user("Recent updates", LlmMessageKind.USER_TURN)
			)));

			assertEquals(List.of("old-rule"), result.payload().eventPolicyChanges().removeRuleIds());
			assertEquals(1, result.payload().eventPolicyChanges().upserts().size());
			assertEquals("mute-system", result.payload().eventPolicyChanges().upserts().getFirst().ruleId());
			assertEquals("social.system_message", result.payload().eventPolicyChanges().upserts().getFirst().match().eventType());
		}
	}

	private static final class TestServer implements AutoCloseable {
		private final HttpServer server;
		private final String responseBody;

		private TestServer(HttpServer server, String responseBody) {
			this.server = server;
			this.responseBody = responseBody;
		}

		private static TestServer start(AtomicReference<String> bodyRef) throws IOException {
			return start(bodyRef, """
				{
				  "choices": [
				    {
				      "message": {
				        "content": "{\\"replyText\\":\\"Sure, I'll follow you.\\",\\"intent\\":{\\"type\\":\\"set_goal\\",\\"goalType\\":\\"FOLLOW_PLAYER\\",\\"targetPlayer\\":\\"Alice\\"},\\"toolRequest\\":null}"
				      }
				    }
				  ],
				  "usage": {
				    "prompt_tokens": 1234,
				    "completion_tokens": 56,
				    "total_tokens": 1290
				  }
				}
				""");
		}

		private static TestServer start(AtomicReference<String> bodyRef, String responseBody) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/chat/completions", exchange -> handle(exchange, bodyRef, responseBody));
			server.start();
			return new TestServer(server, responseBody);
		}

		private static void handle(HttpExchange exchange, AtomicReference<String> bodyRef, String responseBody) throws IOException {
			bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeResponse(exchange, 200, responseBody);
		}

		private int port() {
			return server.getAddress().getPort();
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	private static void writeResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
