package ai.moeru.airicraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class SavedServerService {
	private static final int SERVER_ID_HASH_LENGTH = 8;
	private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(10);

	public List<Map<String, Object>> listServers() {
		MinecraftClient client = requireClient();
		ServerList serverList = loadServerList(client);
		List<Map<String, Object>> servers = new ArrayList<>(serverList.size());
		for (int index = 0; index < serverList.size(); index++) {
			ServerInfo serverInfo = serverList.get(index);
			servers.add(serverPayload(serverInfo, index));
		}
		return servers;
	}

	public Map<String, Object> joinServer(String serverId) {
		MinecraftClient client = requireClient();
		if (isInWorld(client)) {
			throw new SavedServerServiceException("already_in_world", "A world is already loaded");
		}

		ServerEntry entry = findServer(normalizeServerId(serverId));
		if (entry == null) {
			throw new SavedServerServiceException("server_not_found", "Server not found: " + serverId);
		}

		runOnClientThread(client, () -> {
			ConnectScreen.connect(
				client.currentScreen != null ? client.currentScreen : new TitleScreen(),
				client,
				ServerAddress.parse(entry.serverInfo.address),
				entry.serverInfo,
				false,
				new CookieStorage(Map.of())
			);
			return null;
		});

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("started", true);
		payload.put("serverId", serverId(entry.serverInfo));
		payload.put("name", entry.serverInfo.name);
		payload.put("address", entry.serverInfo.address);
		payload.put("serverType", serverTypeName(entry.serverInfo));
		return payload;
	}

	private ServerEntry findServer(String serverId) {
		ServerList serverList = loadServerList(requireClient());
		for (int index = 0; index < serverList.size(); index++) {
			ServerInfo serverInfo = serverList.get(index);
			if (serverId(serverInfo).equals(serverId)) {
				return new ServerEntry(index, serverInfo);
			}
		}
		return null;
	}

	private static ServerList loadServerList(MinecraftClient client) {
		ServerList serverList = new ServerList(client);
		serverList.loadFile();
		return serverList;
	}

	private static Map<String, Object> serverPayload(ServerInfo serverInfo, int index) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("serverId", serverId(serverInfo));
		payload.put("name", nonEmpty(serverInfo.name, "Unnamed Server"));
		payload.put("address", nonEmpty(serverInfo.address, ""));
		payload.put("index", index);
		payload.put("serverType", serverTypeName(serverInfo));
		payload.put("local", serverInfo.isLocal());
		payload.put("realm", serverInfo.isRealm());
		payload.put("resourcePackPolicy", resourcePackPolicy(serverInfo));
		return payload;
	}

	private static String serverId(ServerInfo serverInfo) {
		String key = normalizeAddress(serverInfo.address) + "|" + serverTypeName(serverInfo);
		return shortHash(key);
	}

	private static String normalizeServerId(String serverId) {
		String value = nonEmpty(serverId, "").trim();
		if (value.isEmpty()) {
			throw new SavedServerServiceException("server_not_found", "Server not found: " + serverId);
		}
		return value;
	}

	private static String serverTypeName(ServerInfo serverInfo) {
		return String.valueOf(serverInfo.getServerType()).toLowerCase(Locale.ROOT);
	}

	private static String resourcePackPolicy(ServerInfo serverInfo) {
		return String.valueOf(serverInfo.getResourcePackPolicy()).toLowerCase(Locale.ROOT);
	}

	private static String normalizeAddress(String address) {
		return nonEmpty(address, "").trim().toLowerCase(Locale.ROOT);
	}

	private static String shortHash(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(SERVER_ID_HASH_LENGTH);
			for (byte current : hash) {
				if (builder.length() >= SERVER_ID_HASH_LENGTH) {
					break;
				}
				builder.append(Character.forDigit((current >> 4) & 0x0F, 16));
				builder.append(Character.forDigit(current & 0x0F, 16));
			}
			return builder.substring(0, SERVER_ID_HASH_LENGTH);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Missing SHA-256 implementation", exception);
		}
	}

	private static MinecraftClient requireClient() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			throw new SavedServerServiceException("minecraft_unavailable", "Minecraft client is not initialized");
		}
		return client;
	}

	private static boolean isInWorld(MinecraftClient client) {
		return client.world != null || client.player != null;
	}

	private static <T> T runOnClientThread(MinecraftClient client, java.util.function.Supplier<T> supplier) {
		CompletableFuture<T> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				future.complete(supplier.get());
			}
			catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});

		try {
			return future.get(JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new SavedServerServiceException("saved_server_interrupted", "Joining server was interrupted", exception);
		}
		catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof SavedServerServiceException savedServerServiceException) {
				throw savedServerServiceException;
			}
			throw new SavedServerServiceException("join_failed", nonEmpty(cause.getMessage(), "Failed to join server"), cause);
		}
		catch (java.util.concurrent.TimeoutException exception) {
			throw new SavedServerServiceException("saved_server_timeout", "Timed out while joining server", exception);
		}
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private record ServerEntry(int index, ServerInfo serverInfo) {
	}

	public static final class SavedServerServiceException extends RuntimeException {
		private final String code;

		SavedServerServiceException(String code, String message) {
			super(message);
			this.code = code;
		}

		SavedServerServiceException(String code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
