package ai.moeru.airicraft.agent.tasks;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Optional;

final class MinecraftUnderwaterSourceClassifier {
	private MinecraftUnderwaterSourceClassifier() {
	}

	static Optional<UnderwaterHarvestPolicy.SourceEnvironment> classify(
		MinecraftClient client,
		BlockPos sourcePos,
		BlockState sourceState
	) {
		if (client == null || client.world == null || sourcePos == null || sourceState == null) {
			return Optional.empty();
		}
		boolean sourceContainsFluid = sourceState.getFluidState().isIn(FluidTags.WATER);
		boolean adjacentFluid = false;
		for (Direction direction : Direction.values()) {
			BlockPos adjacent = sourcePos.offset(direction);
			if (client.world.isChunkLoaded(adjacent)
				&& client.world.getFluidState(adjacent).isIn(FluidTags.WATER)) {
				adjacentFluid = true;
				break;
			}
		}
		return UnderwaterHarvestPolicy.classify(
			sourceContainsFluid,
			hasDryStandingApproach(client, sourcePos),
			adjacentFluid
		);
	}

	private static boolean hasDryStandingApproach(MinecraftClient client, BlockPos sourcePos) {
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos feetPos = sourcePos.offset(direction);
			BlockPos headPos = feetPos.up();
			BlockPos supportPos = feetPos.down();
			if (!client.world.isInBuildLimit(feetPos)
				|| !client.world.isInBuildLimit(headPos)
				|| !client.world.isInBuildLimit(supportPos)
				|| !client.world.isChunkLoaded(feetPos)
				|| !client.world.isChunkLoaded(headPos)
				|| !client.world.isChunkLoaded(supportPos)) {
				continue;
			}
			BlockState feet = client.world.getBlockState(feetPos);
			BlockState head = client.world.getBlockState(headPos);
			BlockState support = client.world.getBlockState(supportPos);
			boolean collisionFree = feet.getCollisionShape(client.world, feetPos).isEmpty()
				&& head.getCollisionShape(client.world, headPos).isEmpty();
			boolean dry = client.world.getFluidState(feetPos).isEmpty()
				&& client.world.getFluidState(headPos).isEmpty();
			if (collisionFree && dry
				&& support.isSideSolidFullSquare(client.world, supportPos, Direction.UP)) {
				return true;
			}
		}
		return false;
	}
}
