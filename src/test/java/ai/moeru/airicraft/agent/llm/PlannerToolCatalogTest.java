package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlannerToolCatalogTest {
	@Test
	void parsesDiscoverToolsWithBoundedResultCount() {
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall("""
			{"query":"smelting","maxResults":3}
			"""));

		assertEquals(PlannerToolCatalog.DISCOVER_TOOLS, call.name());
		assertEquals("smelting", call.arguments().get("query").getAsString());
		assertEquals(3, call.arguments().get("maxResults").getAsInt());
	}

	@Test
	void rejectsDiscoverToolsResultCountOutsideTheCardLimit() {
		assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall("""
			{"query":"smelting","maxResults":6}
			""")));
	}

	private static JsonObject toolCall(String arguments) {
		JsonObject toolCall = new JsonObject();
		toolCall.addProperty("id", "call_discover");
		toolCall.addProperty("type", "function");
		JsonObject function = new JsonObject();
		function.addProperty("name", PlannerToolCatalog.DISCOVER_TOOLS);
		function.addProperty("arguments", arguments);
		toolCall.add("function", function);
		return toolCall;
	}
}
