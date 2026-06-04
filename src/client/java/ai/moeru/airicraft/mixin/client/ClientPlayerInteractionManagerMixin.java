package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
	@Unique
	private String airicraft$breakingBlockId;

	@Unique
	private BlockPos airicraft$breakingBlockPos;

	@Inject(method = "breakBlock", at = @At("HEAD"))
	private void airicraft$captureBrokenBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		airicraft$breakingBlockId = null;
		airicraft$breakingBlockPos = null;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread() || client.world == null || pos == null) {
			return;
		}

		BlockState state = client.world.getBlockState(pos);
		if (state == null || state.isAir()) {
			return;
		}
		airicraft$breakingBlockId = Registries.BLOCK.getId(state.getBlock()).toString();
		airicraft$breakingBlockPos = pos.toImmutable();
	}

	@Inject(method = "breakBlock", at = @At("RETURN"))
	private void airicraft$reportBrokenBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (!Boolean.TRUE.equals(cir.getReturnValue()) || airicraft$breakingBlockId == null || airicraft$breakingBlockPos == null) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerMinedBlock(
			airicraft$breakingBlockId,
			airicraft$breakingBlockPos.getX(),
			airicraft$breakingBlockPos.getY(),
			airicraft$breakingBlockPos.getZ()
		);
	}
}
