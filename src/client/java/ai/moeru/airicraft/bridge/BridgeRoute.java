package ai.moeru.airicraft.bridge;

@FunctionalInterface
public interface BridgeRoute {
	void handle(BridgeRouteContext context) throws Exception;
}
