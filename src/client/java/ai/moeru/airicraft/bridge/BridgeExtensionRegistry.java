package ai.moeru.airicraft.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BridgeExtensionRegistry {
	private static final List<BridgeRouteRegistration> ROUTES = new ArrayList<>();

	private BridgeExtensionRegistry() {
	}

	public static synchronized void register(String path, BridgeRoute route) {
		String normalizedPath = normalizePath(path);
		Objects.requireNonNull(route, "route");
		for (BridgeRouteRegistration registration : ROUTES) {
			if (registration.path().equals(normalizedPath)) {
				throw new IllegalArgumentException("Bridge extension route already registered: " + normalizedPath);
			}
		}
		ROUTES.add(new BridgeRouteRegistration(normalizedPath, route));
	}

	public static synchronized List<BridgeRouteRegistration> routes() {
		return List.copyOf(ROUTES);
	}

	static synchronized void clearForTests() {
		ROUTES.clear();
	}

	private static String normalizePath(String path) {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("path must not be blank");
		}
		String normalized = path.trim();
		return normalized.startsWith("/") ? normalized : "/" + normalized;
	}

	public record BridgeRouteRegistration(String path, BridgeRoute route) {
		public BridgeRouteRegistration {
			path = normalizePath(path);
			route = Objects.requireNonNull(route, "route");
		}
	}
}
