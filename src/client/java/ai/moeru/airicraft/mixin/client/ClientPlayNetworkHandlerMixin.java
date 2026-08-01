package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
	@Unique
	private float airicraft$healthBeforeUpdate;

	@Unique
	private boolean airicraft$healthInitializedBeforeUpdate;
	@Unique
	private final Map<ItemPickupAnimationS2CPacket, UUID> airicraft$pickupObservationIds = new IdentityHashMap<>();
	@Unique
	private final Set<UUID> airicraft$reportedPickupObservationIds = new HashSet<>();
	@Unique
	private ItemPickupAnimationS2CPacket airicraft$pickupPacket;
	@Unique
	private UUID airicraft$pickupEntityUuid;
	@Unique
	private int airicraft$pickupPreStackCount;
	@Unique
	private String airicraft$pickupItemId;

	@Inject(method = "onEntityDamage", at = @At("TAIL"))
	private void airicraft$onEntityDamage(EntityDamageS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread() || client.player == null || client.world == null) {
			return;
		}
		if (packet.entityId() != client.player.getId()) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerDamageObserved(packet.createDamageSource(client.world));
	}

	@Inject(
		method = "onHealthUpdate",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;updateHealth(F)V")
	)
	private void airicraft$captureHealthUpdate(HealthUpdateS2CPacket packet, CallbackInfo ci) {
		ClientPlayerEntity player = currentPlayer();
		if (player == null) {
			return;
		}
		airicraft$healthBeforeUpdate = player.getHealth();
		airicraft$healthInitializedBeforeUpdate = ((ClientPlayerEntityAccessor) player).airicraft$isHealthInitialized();
	}

	@Inject(
		method = "onHealthUpdate",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/network/ClientPlayerEntity;updateHealth(F)V",
			shift = At.Shift.AFTER
		)
	)
	private void airicraft$reportHealthUpdate(HealthUpdateS2CPacket packet, CallbackInfo ci) {
		if (currentPlayer() == null) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerHealthUpdated(
			airicraft$healthInitializedBeforeUpdate,
			airicraft$healthBeforeUpdate,
			packet.getHealth()
		);
	}

	@Inject(method = "onPlayerRespawn", at = @At("TAIL"))
	private void airicraft$onPlayerRespawn(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
		AiricraftClient.runtimeController().onPlayerRespawned();
	}

	@Inject(method = "onItemPickupAnimation", at = @At("HEAD"))
	private void airicraft$onItemPickupAnimation(ItemPickupAnimationS2CPacket packet, CallbackInfo ci) {
		airicraft$pickupPacket = packet;
		airicraft$pickupObservationIds.putIfAbsent(packet, UUID.randomUUID());
		airicraft$pickupEntityUuid = null;
		airicraft$pickupPreStackCount = -1;
		airicraft$pickupItemId = null;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread() || client.player == null || client.world == null) {
			return;
		}
		if (!(client.world.getEntityById(packet.getEntityId()) instanceof ItemEntity itemEntity)) {
			return;
		}

		ItemStack stack = itemEntity.getStack();
		if (stack == null || stack.isEmpty()) {
			return;
		}
		airicraft$pickupEntityUuid = itemEntity.getUuid();
		airicraft$pickupPreStackCount = stack.getCount();
		airicraft$pickupItemId = Registries.ITEM.getId(stack.getItem()).toString();
	}

	@Inject(method = "onItemPickupAnimation", at = @At("TAIL"))
	private void airicraft$reportItemPickupAnimation(ItemPickupAnimationS2CPacket packet, CallbackInfo ci) {
		if (packet != airicraft$pickupPacket || airicraft$pickupPreStackCount < 0) {
			return;
		}
		UUID observationId = airicraft$pickupObservationIds.get(packet);
		if (observationId == null || !airicraft$reportedPickupObservationIds.add(observationId)) {
			return;
		}
		airicraft$pickupPacket = null;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread() || client.player == null || client.world == null) {
			return;
		}
		ItemEntity itemEntity = client.world.getEntityById(packet.getEntityId()) instanceof ItemEntity value ? value : null;
		int postStackCount = itemEntity == null ? 0 : itemEntity.getStack().getCount();
		int pickupDelta = itemEntity == null
			? Math.min(Math.max(1, packet.getStackAmount()), airicraft$pickupPreStackCount)
			: Math.max(0, airicraft$pickupPreStackCount - postStackCount);
		if (pickupDelta <= 0) {
			return;
		}
		String itemId = airicraft$pickupItemId;
		if (itemId == null) {
			return;
		}
		PlayerEntity collector = client.world.getEntityById(packet.getCollectorEntityId()) instanceof PlayerEntity playerEntity
			? playerEntity
			: null;
		AiricraftClient.runtimeController().onPlayerItemPickupObserved(
			packet.getEntityId(),
			airicraft$pickupEntityUuid,
			itemId,
			pickupDelta,
			airicraft$pickupPreStackCount,
			collector == null ? null : collector.getUuid(),
			observationId
		);
		if (packet.getCollectorEntityId() == client.player.getId()) {
			AiricraftClient.runtimeController().onPlayerPickedUpItem(itemId, pickupDelta);
		}
	}

	@Inject(method = "onPlayerList", at = @At("TAIL"))
	private void airicraft$onPlayerList(PlayerListS2CPacket packet, CallbackInfo ci) {
		for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
			AiricraftClient.runtimeController().onPlayerJoinedGame(entry.profileId(), entry.profile().getName());
		}
	}

	@Inject(method = "onPlayerRemove", at = @At("TAIL"))
	private void airicraft$onPlayerRemove(PlayerRemoveS2CPacket packet, CallbackInfo ci) {
		for (java.util.UUID profileId : packet.profileIds()) {
			AiricraftClient.runtimeController().onPlayerLeftGame(profileId);
		}
	}

	@Unique
	private static ClientPlayerEntity currentPlayer() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread() || client.player == null || client.world == null) {
			return null;
		}
		return client.player;
	}
}
