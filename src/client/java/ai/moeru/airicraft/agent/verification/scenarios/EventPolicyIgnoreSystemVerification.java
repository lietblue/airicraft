package ai.moeru.airicraft.agent.verification.scenarios;

import ai.moeru.airicraft.agent.events.EventPolicyIntervention;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.verification.ScenarioBuilder;
import ai.moeru.airicraft.agent.verification.VerificationScenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class EventPolicyIgnoreSystemVerification extends VerificationScenario {
	private final BooleanSupplier worldLoaded;
	private final Runnable injectNearbyPlayer;
	private final Runnable installPolicyResponse;
	private final Runnable sendAddressedMessage;
	private final Supplier<List<EventPolicyRule>> activeRules;
	private final Supplier<List<EventPolicyIntervention>> recentInterventions;
	private final Runnable injectSystemMessage;
	private final Runnable setupBypassResponse;
	private final Runnable sendSecondAddressedMessage;
	private final LongSupplier latestSeqNo;
	private final LongPredicate plannerResponseSeenSince;
	private final LongPredicate systemMessageSeenSince;
	private final LongPredicate interventionSeenSince;

	private long postInstallSeqNo;
	private long postSystemSeqNo;

	public EventPolicyIgnoreSystemVerification(
		BooleanSupplier worldLoaded,
		Runnable injectNearbyPlayer,
		Runnable installPolicyResponse,
		Runnable sendAddressedMessage,
		Supplier<List<EventPolicyRule>> activeRules,
		Supplier<List<EventPolicyIntervention>> recentInterventions,
		Runnable injectSystemMessage,
		Runnable setupBypassResponse,
		Runnable sendSecondAddressedMessage,
		LongSupplier latestSeqNo,
		LongPredicate plannerResponseSeenSince,
		LongPredicate systemMessageSeenSince,
		LongPredicate interventionSeenSince
	) {
		this.worldLoaded = Objects.requireNonNull(worldLoaded, "worldLoaded");
		this.injectNearbyPlayer = Objects.requireNonNull(injectNearbyPlayer, "injectNearbyPlayer");
		this.installPolicyResponse = Objects.requireNonNull(installPolicyResponse, "installPolicyResponse");
		this.sendAddressedMessage = Objects.requireNonNull(sendAddressedMessage, "sendAddressedMessage");
		this.activeRules = Objects.requireNonNull(activeRules, "activeRules");
		this.recentInterventions = Objects.requireNonNull(recentInterventions, "recentInterventions");
		this.injectSystemMessage = Objects.requireNonNull(injectSystemMessage, "injectSystemMessage");
		this.setupBypassResponse = Objects.requireNonNull(setupBypassResponse, "setupBypassResponse");
		this.sendSecondAddressedMessage = Objects.requireNonNull(sendSecondAddressedMessage, "sendSecondAddressedMessage");
		this.latestSeqNo = Objects.requireNonNull(latestSeqNo, "latestSeqNo");
		this.plannerResponseSeenSince = Objects.requireNonNull(plannerResponseSeenSince, "plannerResponseSeenSince");
		this.systemMessageSeenSince = Objects.requireNonNull(systemMessageSeenSince, "systemMessageSeenSince");
		this.interventionSeenSince = Objects.requireNonNull(interventionSeenSince, "interventionSeenSince");
	}

	@Override
	public String name() {
		return "event_policy.ignore_system_message";
	}

	@Override
	protected void onStart() {
		postInstallSeqNo = 0L;
		postSystemSeqNo = 0L;
	}

	@Override
	protected void define(ScenarioBuilder builder) {
		builder
			.require("in world", worldLoaded)
			.action("inject nearby player", injectNearbyPlayer)
			.action("setup mock planner response with system-message ignore rule", installPolicyResponse)
			.action("send addressed message to install policy", sendAddressedMessage)
			.waitUntil("policy rule installed", 80, this::policyInstalled)
			.action("record post-install seq no", () -> postInstallSeqNo = latestSeqNo.getAsLong())
			.action("inject system message", injectSystemMessage)
			.assertThat("raw system message recorded", () -> systemMessageSeenSince.test(postInstallSeqNo))
			.assertThat("policy intervention recorded", () -> interventionSeenSince.test(postInstallSeqNo))
			.assertThat("system message did not wake planner", () -> !plannerResponseSeenSince.test(postInstallSeqNo))
			.action("record post-system seq no", () -> postSystemSeqNo = latestSeqNo.getAsLong())
			.action("setup mock planner response for bypass chat", setupBypassResponse)
			.action("send second addressed message", sendSecondAddressedMessage)
			.waitUntil("bypass chat still wakes planner", 80, () -> plannerResponseSeenSince.test(postSystemSeqNo));
	}

	@Override
	protected Map<String, Object> diagnostics() {
		LinkedHashMap<String, Object> diagnostics = new LinkedHashMap<>();
		diagnostics.put("postInstallSeqNo", postInstallSeqNo);
		diagnostics.put("postSystemSeqNo", postSystemSeqNo);
		diagnostics.put("activeRules", activeRules.get());
		diagnostics.put("recentInterventions", recentInterventions.get());
		return Map.copyOf(diagnostics);
	}

	private boolean policyInstalled() {
		return activeRules.get().stream().anyMatch(rule ->
			"mute-system-server".equals(rule.ruleId())
				&& "social.system_message".equals(rule.match().eventType())
		);
	}
}
