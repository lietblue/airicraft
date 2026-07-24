package ai.moeru.airicraft.agent.verification.scenarios;

import ai.moeru.airicraft.agent.verification.ScenarioBuilder;
import ai.moeru.airicraft.agent.verification.VerificationScenario;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

public final class FollowReacquireTargetVerification extends VerificationScenario {
	private final BooleanSupplier worldLoaded;
	private final Runnable injectNearbyPlayer;
	private final LongSupplier latestEventSeqNo;
	private final Runnable setupFirstFollowResponse;
	private final Runnable injectFollowRequest;
	private final LongPredicate acquiredSeenSince;
	private final Runnable disconnectTarget;
	private final LongPredicate lostSeenSince;
	private final BooleanSupplier followGoalActiveAfterLoss;
	private final Runnable reintroduceTarget;
	private final LongPredicate reacquiredSince;
	private final BooleanSupplier followGoalActive;

	private long initialAcquireBaselineSeqNo;
	private long lostBaselineSeqNo;
	private long reacquireBaselineSeqNo;

	public FollowReacquireTargetVerification(
		BooleanSupplier worldLoaded,
		Runnable injectNearbyPlayer,
		LongSupplier latestEventSeqNo,
		Runnable setupFirstFollowResponse,
		Runnable injectFollowRequest,
		LongPredicate acquiredSeenSince,
		Runnable disconnectTarget,
		LongPredicate lostSeenSince,
		BooleanSupplier followGoalActiveAfterLoss,
		Runnable reintroduceTarget,
		LongPredicate reacquiredSince,
		BooleanSupplier followGoalActive
	) {
		this.worldLoaded = Objects.requireNonNull(worldLoaded, "worldLoaded");
		this.injectNearbyPlayer = Objects.requireNonNull(injectNearbyPlayer, "injectNearbyPlayer");
		this.latestEventSeqNo = Objects.requireNonNull(latestEventSeqNo, "latestEventSeqNo");
		this.setupFirstFollowResponse = Objects.requireNonNull(setupFirstFollowResponse, "setupFirstFollowResponse");
		this.injectFollowRequest = Objects.requireNonNull(injectFollowRequest, "injectFollowRequest");
		this.acquiredSeenSince = Objects.requireNonNull(acquiredSeenSince, "acquiredSeenSince");
		this.disconnectTarget = Objects.requireNonNull(disconnectTarget, "disconnectTarget");
		this.lostSeenSince = Objects.requireNonNull(lostSeenSince, "lostSeenSince");
		this.followGoalActiveAfterLoss = Objects.requireNonNull(followGoalActiveAfterLoss, "followGoalActiveAfterLoss");
		this.reintroduceTarget = Objects.requireNonNull(reintroduceTarget, "reintroduceTarget");
		this.reacquiredSince = Objects.requireNonNull(reacquiredSince, "reacquiredSince");
		this.followGoalActive = Objects.requireNonNull(followGoalActive, "followGoalActive");
	}

	@Override
	public String name() {
		return "follow.reacquire_target";
	}

	@Override
	protected void define(ScenarioBuilder builder) {
		builder
			.require("in world", worldLoaded)
			.action("inject nearby player", injectNearbyPlayer)
			.action("capture initial acquire baseline", () -> initialAcquireBaselineSeqNo = latestEventSeqNo.getAsLong())
			.action("setup first follow response", setupFirstFollowResponse)
			.action("inject first follow request", injectFollowRequest)
			.waitUntil("initial target acquired", 300, () -> acquiredSeenSince.test(initialAcquireBaselineSeqNo))
			.action("capture lost baseline", () -> lostBaselineSeqNo = latestEventSeqNo.getAsLong())
			.action("disconnect target", disconnectTarget)
			.waitUntil("target lost", 100, () -> lostSeenSince.test(lostBaselineSeqNo))
			.assertThat("follow goal remains active after loss", followGoalActiveAfterLoss)
			.action("capture reacquire baseline", () -> reacquireBaselineSeqNo = latestEventSeqNo.getAsLong())
			.action("reintroduce target", reintroduceTarget)
			.waitUntil("target reacquired without another request", 300, () -> reacquiredSince.test(reacquireBaselineSeqNo))
			.assertThat("follow goal remains active", followGoalActive);
	}
}
