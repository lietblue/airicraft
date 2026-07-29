package ai.moeru.airicraft.agent.actions;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Owns asynchronous loot-knowledge loading so datapack I/O never blocks the render thread.
 */
public final class MinecraftBlockAcquisitionKnowledgeService {
	private static final Object CLASSPATH_SOURCE = new Object();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "airicraft-block-acquisition-loader");
		thread.setDaemon(true);
		return thread;
	});

	private Object sourceIdentity;
	private Future<BlockAcquisitionIndex> pending;
	private Snapshot snapshot = Snapshot.unavailable();

	public synchronized Snapshot tick(MinecraftClient client) {
		Object nextIdentity = sourceIdentity(client);
		if (nextIdentity == null) {
			reset();
			return snapshot;
		}
		if (!Objects.equals(sourceIdentity, nextIdentity)) {
			cancelPending();
			sourceIdentity = nextIdentity;
			ResourceManager resourceManager = resourceManager(client);
			pending = executor.submit(() -> MinecraftBlockAcquisitionLoader.load(resourceManager));
			snapshot = Snapshot.loading(snapshot.index());
		}
		if (pending == null || !pending.isDone()) {
			return snapshot;
		}
		try {
			BlockAcquisitionIndex index = pending.get();
			snapshot = Snapshot.ready(index);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			snapshot = Snapshot.failed("interrupted", snapshot.index());
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			snapshot = Snapshot.failed(
				cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(),
				snapshot.index()
			);
		}
		finally {
			pending = null;
		}
		return snapshot;
	}

	public synchronized Snapshot snapshot() {
		return snapshot;
	}

	public synchronized void reset() {
		cancelPending();
		sourceIdentity = null;
		snapshot = Snapshot.unavailable();
	}

	public synchronized void shutdown() {
		reset();
		executor.shutdownNow();
	}

	private void cancelPending() {
		if (pending != null) {
			pending.cancel(true);
			pending = null;
		}
	}

	private static ResourceManager resourceManager(MinecraftClient client) {
		MinecraftServer server = client == null ? null : client.getServer();
		return server == null ? null : server.getResourceManager();
	}

	private static Object sourceIdentity(MinecraftClient client) {
		if (client == null) {
			return null;
		}
		ResourceManager resourceManager = resourceManager(client);
		if (resourceManager != null) {
			return resourceManager;
		}
		Object networkHandler = client.getNetworkHandler();
		return networkHandler == null ? CLASSPATH_SOURCE : networkHandler;
	}

	public enum Status {
		UNAVAILABLE,
		LOADING,
		READY,
		FAILED
	}

	public record Snapshot(Status status, BlockAcquisitionIndex index, String error) {
		public Snapshot {
			status = status == null ? Status.UNAVAILABLE : status;
			index = index == null ? BlockAcquisitionIndex.empty() : index;
			error = error == null ? "" : error;
		}

		private static Snapshot unavailable() {
			return new Snapshot(Status.UNAVAILABLE, BlockAcquisitionIndex.empty(), "");
		}

		private static Snapshot loading(BlockAcquisitionIndex previousIndex) {
			return new Snapshot(Status.LOADING, previousIndex, "");
		}

		private static Snapshot ready(BlockAcquisitionIndex index) {
			return new Snapshot(Status.READY, index, "");
		}

		private static Snapshot failed(String error, BlockAcquisitionIndex previousIndex) {
			return new Snapshot(Status.FAILED, previousIndex, error);
		}
	}
}
