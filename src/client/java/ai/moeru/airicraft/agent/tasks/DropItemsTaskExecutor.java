package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class DropItemsTaskExecutor implements WorldTaskExecutor {
	private static final int BUSY_SCREEN_TIMEOUT_TICKS = 100;
	private static final String INVENTORY_BUSY = "inventory_busy";
	private static final String INVENTORY_BUSY_SCREEN = "inventory_busy_screen";
	private static final String INVENTORY_SCREEN_DISMISSED = "inventory_screen_dismissed";

	private final Supplier<MinecraftClient> clientSupplier;

	private WorldTaskRequest appliedTask;
	private int busyScreenTicks;
	private boolean terminalEventEmitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public DropItemsTaskExecutor() {
		this(MinecraftClient::getInstance);
	}

	DropItemsTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.DROP_ITEMS) {
			reset();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}

		if (!itemDropActuationAllowed(sessionSnapshot)) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.interactionManager == null || player == null || client.world == null) {
			return fail(request, "world_unavailable");
		}
		if (dismissCurrentScreenIfSafe(client, player)) {
			busyScreenTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, INVENTORY_SCREEN_DISMISSED);
			return Optional.empty();
		}
		Optional<String> readinessFailure = readinessFailure(client, player);
		if (readinessFailure.isPresent()) {
			if (INVENTORY_BUSY_SCREEN.equals(readinessFailure.get()) && shouldWaitForBusyScreen(busyScreenTicks)) {
				busyScreenTicks++;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, INVENTORY_BUSY);
				return Optional.empty();
			}
			return fail(request, INVENTORY_BUSY);
		}
		busyScreenTicks = 0;

		DropItemsStepArgs args = request.dropItems();
		ScreenHandler handler = player.currentScreenHandler;
		List<DropSlot> matchingSlots = matchingSlots(handler, args.itemId());
		int available = matchingSlots.stream().mapToInt(DropSlot::count).sum();
		if (available == 0) {
			return fail(request, "item_not_found");
		}
		if (available < args.quantity()) {
			return fail(request, "insufficient_items");
		}

		for (DropClick click : planDropClicks(matchingSlots, args.quantity())) {
			performDropClick(client, player, handler, click);
		}
		return complete(request);
	}

	private static Optional<String> readinessFailure(MinecraftClient client, ClientPlayerEntity player) {
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return Optional.of(INVENTORY_BUSY);
		}
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) {
			return Optional.of(INVENTORY_BUSY_SCREEN);
		}
		if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
			return Optional.of(INVENTORY_BUSY);
		}
		return Optional.empty();
	}

	private static boolean dismissCurrentScreenIfSafe(MinecraftClient client, ClientPlayerEntity player) {
		if (!shouldDismissBusyScreen(currentScreenName(client))) {
			return false;
		}
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return false;
		}
		if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
			return false;
		}
		ScreenCloseSafety.clearScreen(client, "drop_items_screen_dismiss");
		return true;
	}

	private static String currentScreenName(MinecraftClient client) {
		return client.currentScreen == null ? null : client.currentScreen.getClass().getSimpleName();
	}

	static boolean shouldDismissBusyScreen(String screenName) {
		return "ChatScreen".equals(screenName) || "GameMenuScreen".equals(screenName);
	}

	static boolean itemDropActuationAllowed(SessionSnapshot sessionSnapshot) {
		return sessionSnapshot != null && sessionSnapshot.companionActuationAllowed();
	}

	static boolean shouldWaitForBusyScreen(int busyScreenTicks) {
		return busyScreenTicks < BUSY_SCREEN_TIMEOUT_TICKS;
	}

	private static List<DropSlot> matchingSlots(ScreenHandler handler, String itemId) {
		ArrayList<DropSlot> slots = new ArrayList<>();
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (stack.isEmpty()) {
				continue;
			}
			String stackItemId = Registries.ITEM.getId(stack.getItem()).toString();
			if (itemId.equals(stackItemId)) {
				slots.add(new DropSlot(slot, stack.getCount()));
			}
		}
		return List.copyOf(slots);
	}

	static List<DropClick> planDropClicks(List<DropSlot> slots, int quantity) {
		ArrayList<DropClick> clicks = new ArrayList<>();
		int remaining = quantity;
		for (DropSlot slot : slots) {
			if (remaining <= 0) {
				break;
			}
			int dropCount = Math.min(slot.count(), remaining);
			if (dropCount > 0) {
				clicks.add(new DropClick(slot.slotId(), dropCount));
				remaining -= dropCount;
			}
		}
		return List.copyOf(clicks);
	}

	private static void performDropClick(MinecraftClient client, ClientPlayerEntity player, ScreenHandler handler, DropClick click) {
		int remaining = click.count();
		while (remaining > 0) {
			ItemStack currentStack = handler.getSlot(click.slotId()).getStack();
			if (!currentStack.isEmpty() && remaining >= currentStack.getCount()) {
				int stackCount = currentStack.getCount();
				client.interactionManager.clickSlot(handler.syncId, click.slotId(), 1, SlotActionType.THROW, player);
				remaining -= stackCount;
			}
			else {
				client.interactionManager.clickSlot(handler.syncId, click.slotId(), 0, SlotActionType.THROW, player);
				remaining--;
			}
		}
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request) {
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, "dropped_items");
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, completionMessage(request.dropItems()), null));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, String reason) {
		snapshot = snapshot(TaskExecutionState.FAILED, request, reason);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, reason, null));
	}

	private static String completionMessage(DropItemsStepArgs args) {
		return "dropped_items itemId=" + args.itemId()
			+ " quantity=" + args.quantity()
			+ (args.targetPlayer() == null ? "" : " targetPlayer=" + args.targetPlayer());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "ItemDrop", event, null, null);
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.dropItems(), right.dropItems());
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		reset();
	}

	@Override
	public void shutdown() {
		reset();
	}

	private void reset() {
		appliedTask = null;
		busyScreenTicks = 0;
		terminalEventEmitted = false;
		snapshot = TaskExecutionSnapshot.idle();
	}

	record DropSlot(int slotId, int count) {
	}

	record DropClick(int slotId, int count) {
	}
}
