package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ActionFactCondition(
	ActionFactType factType,
	Map<String, String> queryKeys,
	Map<String, Integer> minimums
) {
	public ActionFactCondition {
		factType = Objects.requireNonNull(factType, "factType");
		queryKeys = copyStringMap(queryKeys);
		minimums = copyIntegerMap(minimums);
	}

	public boolean satisfiedBy(ActionFact fact) {
		if (fact == null || !fact.identity().matches(factType, queryKeys)) {
			return false;
		}
		for (Map.Entry<String, Integer> minimum : minimums.entrySet()) {
			Object value = fact.payload().get(minimum.getKey());
			if (!(value instanceof Number number) || number.intValue() < minimum.getValue()) {
				return false;
			}
		}
		return true;
	}

	private static Map<String, String> copyStringMap(Map<String, String> input) {
		return input == null || input.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(input));
	}

	private static Map<String, Integer> copyIntegerMap(Map<String, Integer> input) {
		return input == null || input.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(input));
	}
}
