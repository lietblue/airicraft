package ai.moeru.airicraft.agent.actions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PersistentActionFactStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final Pattern SAFE_FILENAME = Pattern.compile("[^a-zA-Z0-9._-]+");

	private final Path root;

	public PersistentActionFactStore(Path root) {
		this.root = Objects.requireNonNull(root, "root");
	}

	public static PersistentActionFactStore defaults() {
		return new PersistentActionFactStore(defaultRoot());
	}

	public static Path defaultRoot() {
		return Paths.get(System.getProperty("user.home"), ".airicraft", "action-facts");
	}

	public synchronized List<ActionFact> list(String worldId) {
		return readFile(worldId).facts().stream()
			.map(PersistentActionFactStore::toFact)
			.filter(Objects::nonNull)
			.sorted(PersistentActionFactStore::compareFacts)
			.toList();
	}

	public synchronized ActionFactStore load(String worldId) {
		ActionFactStore store = new ActionFactStore();
		for (ActionFact fact : list(worldId)) {
			store.upsert(fact);
		}
		return store;
	}

	public synchronized int save(String worldId, Iterable<ActionFact> facts) {
		Objects.requireNonNull(facts, "facts");
		ActionFactStore merged = load(worldId);
		int persisted = 0;
		for (ActionFact fact : facts) {
			if (fact != null && ActionFactPersistencePolicy.persistedByDefault(fact)) {
				merged.upsert(fact);
				persisted++;
			}
		}
		writeFile(worldId, toFile(worldId, merged.queryAll()));
		return persisted;
	}

	public synchronized int upsert(String worldId, ActionFact fact) {
		if (fact == null || !ActionFactPersistencePolicy.persistedByDefault(fact)) {
			return 0;
		}
		return save(worldId, List.of(fact));
	}

	public synchronized int clear(String worldId) {
		List<ActionFact> existing = list(worldId);
		Path file = fileFor(worldId);
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to clear action facts for " + worldId, exception);
		}
		return existing.size();
	}

	private ActionFactFile readFile(String worldId) {
		Path file = fileFor(worldId);
		if (!Files.exists(file)) {
			return new ActionFactFile(requireWorldId(worldId), List.of());
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			ActionFactFile parsed = GSON.fromJson(reader, ActionFactFile.class);
			return parsed == null ? new ActionFactFile(requireWorldId(worldId), List.of()) : parsed;
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to read action facts for " + worldId, exception);
		}
	}

	private void writeFile(String worldId, ActionFactFile file) {
		Path path = fileFor(worldId);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(file, writer);
			}
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to write action facts for " + worldId, exception);
		}
	}

	private static ActionFactFile toFile(String worldId, List<ActionFact> facts) {
		List<ActionFactRecord> records = facts.stream()
			.filter(ActionFactPersistencePolicy::persistedByDefault)
			.sorted(PersistentActionFactStore::compareFacts)
			.map(PersistentActionFactStore::toRecord)
			.toList();
		return new ActionFactFile(requireWorldId(worldId), records);
	}

	private Path fileFor(String worldId) {
		return root.resolve(safeWorldFileName(worldId) + ".json");
	}

	private static String safeWorldFileName(String worldId) {
		return SAFE_FILENAME.matcher(requireWorldId(worldId)).replaceAll("_");
	}

	private static String requireWorldId(String worldId) {
		if (worldId == null || worldId.isBlank()) {
			throw new IllegalArgumentException("worldId is required");
		}
		return worldId.trim();
	}

	private static ActionFactRecord toRecord(ActionFact fact) {
		return new ActionFactRecord(
			fact.identity().type().id(),
			fact.identity().keys(),
			fact.payload(),
			fact.provenance().name(),
			fact.observedTick(),
			fact.staleAfterTick()
		);
	}

	private static ActionFact toFact(ActionFactRecord record) {
		if (record == null) {
			return null;
		}
		ActionFactType type = ActionFactType.fromId(record.type()).orElse(null);
		if (type == null) {
			return null;
		}
		ActionFactProvenance provenance;
		try {
			provenance = ActionFactProvenance.valueOf(record.provenance());
		}
		catch (RuntimeException exception) {
			return null;
		}
		return new ActionFact(
			new ActionFactIdentity(type, stringMap(record.keys())),
			record.payload() == null ? Map.of() : new LinkedHashMap<>(record.payload()),
			provenance,
			record.observedTick(),
			record.staleAfterTick()
		);
	}

	private static Map<String, String> stringMap(Map<String, String> keys) {
		if (keys == null || keys.isEmpty()) {
			return Map.of();
		}
		return new LinkedHashMap<>(keys);
	}

	private static int compareFacts(ActionFact left, ActionFact right) {
		int byType = left.identity().type().id().compareTo(right.identity().type().id());
		if (byType != 0) {
			return byType;
		}
		return left.identity().keys().toString().compareTo(right.identity().keys().toString());
	}

	private record ActionFactFile(String worldId, List<ActionFactRecord> facts) {
		private ActionFactFile {
			facts = facts == null ? List.of() : new ArrayList<>(facts);
		}
	}

	private record ActionFactRecord(
		String type,
		Map<String, String> keys,
		Map<String, Object> payload,
		String provenance,
		long observedTick,
		long staleAfterTick
	) {
		private ActionFactRecord {
			keys = keys == null ? Map.of() : new LinkedHashMap<>(keys);
			payload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
		}
	}
}
