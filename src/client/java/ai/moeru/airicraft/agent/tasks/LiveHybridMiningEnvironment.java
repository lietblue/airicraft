package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Minecraft/Baritone observation adapter for {@link HybridMiningTaskExecutor}.
 * It does not own either actuator; it only snapshots facts and drains the
 * cancellation event during an explicit ownership handoff.
 */
public final class LiveHybridMiningEnvironment implements
	HybridMiningTaskExecutor.UnderwaterSourceProbe,
	HybridMiningTaskExecutor.MiningProgressProbe,
	HybridMiningTaskExecutor.BaritoneReleaseProbe {
	private final Supplier<MinecraftClient> clientSupplier;
	private final BaritoneFacade baritone;
	private boolean releaseStarted;

	public LiveHybridMiningEnvironment(BaritoneFacade baritone) {
		this(MinecraftClient::getInstance, baritone);
	}

	LiveHybridMiningEnvironment(Supplier<MinecraftClient> clientSupplier, BaritoneFacade baritone) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritone = Objects.requireNonNull(baritone, "baritone");
	}

	@Override
	public Optional<UnderwaterHarvestStepArgs> findFallback(WorldTaskRequest request) {
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		GoalMineSpec mineSpec = request == null || request.goal() == null ? null : request.goal().mineSpec();
		if (client == null || client.world == null || player == null || mineSpec == null) {
			return Optional.empty();
		}
		BlockPos origin = player.getBlockPos().toImmutable();
		Set<String> requestedBlocks = new HashSet<>(mineSpec.blockIds());
		for (int x = origin.getX() - UnderwaterHarvestPolicy.HORIZONTAL_RADIUS;
			x <= origin.getX() + UnderwaterHarvestPolicy.HORIZONTAL_RADIUS;
			x++) {
			for (int z = origin.getZ() - UnderwaterHarvestPolicy.HORIZONTAL_RADIUS;
				z <= origin.getZ() + UnderwaterHarvestPolicy.HORIZONTAL_RADIUS;
				z++) {
				for (int y = origin.getY() - UnderwaterHarvestPolicy.VERTICAL_RADIUS;
					y <= origin.getY() + UnderwaterHarvestPolicy.VERTICAL_RADIUS;
					y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!client.world.isInBuildLimit(pos) || !client.world.isChunkLoaded(pos)) {
						continue;
					}
					BlockState state = client.world.getBlockState(pos);
					if (!requestedBlocks.contains(Registries.BLOCK.getId(state.getBlock()).toString())) {
						continue;
					}
					boolean containsFluid = state.getFluidState().isIn(FluidTags.WATER);
					boolean adjacentFluid = false;
					for (Direction direction : Direction.values()) {
						BlockPos adjacent = pos.offset(direction);
						if (client.world.isChunkLoaded(adjacent)
							&& client.world.getFluidState(adjacent).isIn(FluidTags.WATER)) {
							adjacentFluid = true;
							break;
						}
					}
					if (UnderwaterHarvestPolicy.classify(containsFluid, adjacentFluid).underwater()) {
						return Optional.of(new UnderwaterHarvestStepArgs(new GoalPosition(
							origin.getX(),
							origin.getY(),
							origin.getZ(),
							true
						)));
					}
				}
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<HybridMiningPolicy.ProgressSample> observeProgress() {
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) {
			return Optional.empty();
		}
		return Optional.of(new HybridMiningPolicy.ProgressSample(
			player.isTouchingWater(),
			player.getX(),
			player.getY(),
			player.getZ()
		));
	}

	@Override
	public void beginRelease() {
		releaseStarted = true;
	}

	@Override
	public HybridMiningPolicy.ReleaseStatus observeRelease() {
		if (!releaseStarted) {
			return new HybridMiningPolicy.ReleaseStatus(false, false);
		}
		return new HybridMiningPolicy.ReleaseStatus(
			!baritone.processActive(),
			!baritone.cancellationPending()
		);
	}

	@Override
	public void resetRelease() {
		releaseStarted = false;
	}

}
