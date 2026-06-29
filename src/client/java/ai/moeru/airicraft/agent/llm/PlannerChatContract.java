package ai.moeru.airicraft.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PlannerChatContract {
	public static final int MAX_MESSAGE_LENGTH = 80;
	public static final int MAX_MESSAGE_COUNT = 4;
	public static final int MAX_DELAY_TICKS = 200;

	private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]]+\\]\\([^\\)]+\\)");

	private PlannerChatContract() {
	}

	public static ValidationResult validateMessages(List<PlannerChatMessage> messages, String fieldName) {
		ArrayList<String> violations = new ArrayList<>();
		if (messages == null || messages.isEmpty()) {
			return ValidationResult.ok();
		}
		if (messages.size() > MAX_MESSAGE_COUNT) {
			violations.add(fieldName + " has too many messages");
		}
		for (int index = 0; index < messages.size(); index++) {
			PlannerChatMessage message = messages.get(index);
			String prefix = fieldName + "[" + index + "]";
			validateText(message == null ? "" : message.text(), prefix, violations);
			if (message != null && message.delayTicks() > MAX_DELAY_TICKS) {
				violations.add(prefix + " delay is too long");
			}
		}
		return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.error(String.join("; ", violations));
	}

	public static ValidationResult validateText(String text, String fieldName) {
		ArrayList<String> violations = new ArrayList<>();
		validateText(text, fieldName, violations);
		return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.error(String.join("; ", violations));
	}

	public static List<PlannerChatMessage> contractMessages(List<PlannerChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		return messages.stream()
			.limit(MAX_MESSAGE_COUNT)
			.map(message -> new PlannerChatMessage(
				contractText(message == null ? "" : message.text()),
				Math.min(message == null ? 0 : message.delayTicks(), MAX_DELAY_TICKS)
			))
			.filter(message -> !message.text().isBlank())
			.toList();
	}

	public static String contractText(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String sanitized = text
			.replace("```", "")
			.replace("`", "")
			.replace("*", "")
			.replace("_", "")
			.replace("#", "")
			.replace(">", "")
			.replace("|", " ")
			.replace('\r', ' ')
			.replace('\n', ' ')
			.replace('\t', ' ');
		sanitized = MARKDOWN_LINK.matcher(sanitized).replaceAll("");
		sanitized = sanitized.replaceAll("\\s+", " ").strip();
		while (sanitized.startsWith("/")) {
			sanitized = sanitized.substring(1).stripLeading();
		}
		if (sanitized.length() > MAX_MESSAGE_LENGTH) {
			sanitized = sanitized.substring(0, MAX_MESSAGE_LENGTH).stripTrailing();
		}
		return sanitized;
	}

	private static void validateText(String text, String fieldName, List<String> violations) {
		if (text == null || text.isBlank()) {
			violations.add(fieldName + " is blank");
			return;
		}
		if (text.length() > MAX_MESSAGE_LENGTH) {
			violations.add(fieldName + " is over " + MAX_MESSAGE_LENGTH + " characters");
		}
		if (text.contains("\n") || text.contains("\r")) {
			violations.add(fieldName + " is multiline");
		}
		String trimmed = text.strip();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (
			trimmed.startsWith("/")
				|| trimmed.startsWith("#")
				|| trimmed.startsWith(">")
				|| trimmed.startsWith("- ")
				|| trimmed.startsWith("* ")
				|| trimmed.startsWith("+ ")
				|| lower.startsWith("```")
				|| text.contains("```")
				|| MARKDOWN_LINK.matcher(text).find()
		) {
			violations.add(fieldName + " uses command or markdown formatting");
		}
	}

	public record ValidationResult(boolean valid, String message) {
		public static ValidationResult ok() {
			return new ValidationResult(true, "");
		}

		public static ValidationResult error(String message) {
			return new ValidationResult(false, message == null ? "" : message);
		}
	}
}
