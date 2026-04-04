package ai.moeru.airicraft.agent;

import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.Registries;

import java.util.LinkedHashMap;
import java.util.Map;

final class LocalDamageTracker {
	private PendingDamageObservation pendingObservation;

	static boolean shouldCaptureHealthLoss(boolean healthInitialized, float healthBefore, float healthAfter) {
		return healthInitialized
			&& Float.isFinite(healthBefore)
			&& Float.isFinite(healthAfter)
			&& healthAfter < healthBefore;
	}

	void observeDamageSource(long tick, DamageSource damageSource) {
		if (damageSource == null) {
			return;
		}
		observeDamage(
			tick,
			damageTypeId(damageSource),
			entityName(damageSource.getAttacker()),
			entityTypeId(damageSource.getAttacker()),
			entityTypeId(damageSource.getSource())
		);
	}

	void observeDamage(
		long tick,
		String damageTypeId,
		String attackerName,
		String attackerEntityTypeId,
		String directSourceEntityTypeId
	) {
		pendingObservation = new PendingDamageObservation(
			tick,
			blankToNull(damageTypeId),
			blankToNull(attackerName),
			blankToNull(attackerEntityTypeId),
			blankToNull(directSourceEntityTypeId)
		);
	}

	Map<String, Object> consumeDamage(boolean healthInitialized, long tick, float healthBefore, float healthAfter) {
		if (!shouldCaptureHealthLoss(healthInitialized, healthBefore, healthAfter)) {
			return null;
		}

		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("actor", "self");
		payload.put("amount", healthBefore - healthAfter);
		payload.put("healthBefore", healthBefore);
		payload.put("healthAfter", healthAfter);
		payload.put("fatal", healthAfter <= 0.0F);

		if (pendingObservation != null) {
			long age = tick - pendingObservation.captureTick();
			if (age >= 0L) {
				if (age <= 1L) {
					putIfPresent(payload, "damageTypeId", pendingObservation.damageTypeId());
					putIfPresent(payload, "attackerName", pendingObservation.attackerName());
					putIfPresent(payload, "attackerEntityTypeId", pendingObservation.attackerEntityTypeId());
					putIfPresent(payload, "directSourceEntityTypeId", pendingObservation.directSourceEntityTypeId());
				}
				pendingObservation = null;
			}
		}

		return Map.copyOf(payload);
	}

	void pruneStale(long tick) {
		if (pendingObservation != null && tick - pendingObservation.captureTick() > 2L) {
			pendingObservation = null;
		}
	}

	void clear() {
		pendingObservation = null;
	}

	PendingDamageObservation pendingObservation() {
		return pendingObservation;
	}

	private static String damageTypeId(DamageSource damageSource) {
		if (damageSource == null) {
			return null;
		}
		return damageSource.getTypeRegistryEntry()
			.getKey()
			.map(key -> key.getValue().toString())
			.orElseGet(damageSource::getName);
	}

	private static String entityName(Entity entity) {
		if (entity == null || entity.getName() == null) {
			return null;
		}
		return blankToNull(entity.getName().getString());
	}

	private static String entityTypeId(Entity entity) {
		if (entity == null || entity.getType() == null) {
			return null;
		}
		return blankToNull(Registries.ENTITY_TYPE.getId(entity.getType()).toString());
	}

	private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
		if (value == null) {
			return;
		}
		String text = String.valueOf(value);
		if (!text.isBlank()) {
			payload.put(key, value);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	record PendingDamageObservation(
		long captureTick,
		String damageTypeId,
		String attackerName,
		String attackerEntityTypeId,
		String directSourceEntityTypeId
	) {
	}
}
