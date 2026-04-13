package ai.moeru.airicraft.agent.tasks;

public record CollectResourceStepArgs(
	TaskResourceKind resourceKind,
	int quantity,
	String deliveryPolicy
) {
	public CollectResourceStepArgs {
		if (resourceKind == null) {
			throw new IllegalArgumentException("resourceKind must not be null");
		}
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive");
		}
	}
}
