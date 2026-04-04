package ai.moeru.airicraft.agent.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class SemanticEventBuffer {
	private final int capacity;
	private final LongSupplier clock;
	private final List<SemanticEvent> events = new ArrayList<>();

	private long nextSeqNo = 1L;
	private long droppedCount;

	public SemanticEventBuffer(int capacity) {
		this(capacity, System::currentTimeMillis);
	}

	public SemanticEventBuffer(int capacity, LongSupplier clock) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive");
		}
		this.capacity = capacity;
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public SemanticEvent append(long tick, String type, Map<String, Object> payload) {
		return append(tick, clock.getAsLong(), type, payload);
	}

	public SemanticEvent append(long tick, long timestampMs, String type, Map<String, Object> payload) {
		Objects.requireNonNull(type, "type");
		Map<String, Object> safePayload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
		SemanticEvent event = new SemanticEvent(nextSeqNo++, tick, timestampMs, type, Map.copyOf(safePayload));
		if (events.size() == capacity) {
			events.remove(0);
			droppedCount++;
		}
		events.add(event);
		return event;
	}

	public SemanticEventQueryResult query(Long sinceSeqNo) {
		long oldestSeqNo = events.isEmpty() ? nextSeqNo : events.get(0).seqNo();
		long latestSeqNo = events.isEmpty() ? 0L : events.get(events.size() - 1).seqNo();
		long effectiveSince = sinceSeqNo == null ? 0L : sinceSeqNo.longValue();

		List<SemanticEvent> matches = new ArrayList<>();
		for (SemanticEvent event : events) {
			if (event.seqNo() > effectiveSince) {
				matches.add(event);
			}
		}

		boolean truncated = sinceSeqNo != null && oldestSeqNo > 0L && sinceSeqNo < oldestSeqNo;
		return new SemanticEventQueryResult(oldestSeqNo, latestSeqNo, truncated, List.copyOf(matches));
	}

	public long latestSeqNo() {
		return events.isEmpty() ? 0L : events.get(events.size() - 1).seqNo();
	}

	public boolean containsType(String type) {
		for (SemanticEvent event : events) {
			if (event.type().equals(type)) {
				return true;
			}
		}
		return false;
	}

	public boolean containsTypeSince(long sinceSeqNo, String type) {
		return countTypeSince(sinceSeqNo, type) > 0;
	}

	public boolean containsTypeForPlayer(String type, String playerName) {
		for (SemanticEvent event : events) {
			if (!event.type().equals(type)) {
				continue;
			}
			Object player = event.payload().get("player");
			if (playerName.equals(player)) {
				return true;
			}
		}
		return false;
	}

	public boolean containsTypeForPlayerSince(long sinceSeqNo, String type, String playerName) {
		return countTypeForPlayerSince(sinceSeqNo, type, playerName) > 0;
	}

	public int countTypeSince(long sinceSeqNo, String type) {
		int count = 0;
		for (SemanticEvent event : events) {
			if (event.seqNo() > sinceSeqNo && event.type().equals(type)) {
				count++;
			}
		}
		return count;
	}

	public int countTypeForPlayerSince(long sinceSeqNo, String type, String playerName) {
		int count = 0;
		for (SemanticEvent event : events) {
			if (event.seqNo() <= sinceSeqNo || !event.type().equals(type)) {
				continue;
			}
			Object player = event.payload().get("player");
			if (playerName.equals(player)) {
				count++;
			}
		}
		return count;
	}

	public void clear() {
		events.clear();
		droppedCount = 0L;
		nextSeqNo = 1L;
	}

	public int size() {
		return events.size();
	}

	public long droppedCount() {
		return droppedCount;
	}
}
