package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.Airicraft;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class DebugLoggingSpanExporter implements SpanExporter {
	private static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("input.value");
	private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("output.value");
	private static final AttributeKey<String> AIRICRAFT_THREAD_ID = AttributeKey.stringKey("airicraft.thread_id");
	private static final AttributeKey<String> WANDB_THREAD_ID = AttributeKey.stringKey("wandb.thread_id");
	private static final AttributeKey<Boolean> AIRICRAFT_TURN = AttributeKey.booleanKey("airicraft.turn");
	private static final AttributeKey<Boolean> WANDB_IS_TURN = AttributeKey.booleanKey("wandb.is_turn");

	private final SpanExporter delegate;

	DebugLoggingSpanExporter(SpanExporter delegate) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
	}

	@Override
	public CompletableResultCode export(Collection<SpanData> spans) {
		List<SpanData> copiedSpans = List.copyOf(spans);
		Airicraft.LOGGER.info(
			"Observability export batch size={} spans={}",
			copiedSpans.size(),
			copiedSpans.stream().map(DebugLoggingSpanExporter::summarize).collect(Collectors.joining(" | "))
		);
		CompletableResultCode result = delegate.export(copiedSpans);
		result.whenComplete(() -> {
			if (result.isSuccess()) {
				Airicraft.LOGGER.info("Observability export succeeded spanNames={}", spanNames(copiedSpans));
			}
			else {
				Airicraft.LOGGER.warn("Observability export failed spanNames={}", spanNames(copiedSpans));
			}
		});
		return result;
	}

	@Override
	public CompletableResultCode flush() {
		return delegate.flush();
	}

	@Override
	public CompletableResultCode shutdown() {
		return delegate.shutdown();
	}

	private static String spanNames(List<SpanData> spans) {
		return spans.stream().map(SpanData::getName).collect(Collectors.joining(","));
	}

	private static String summarize(SpanData span) {
		String threadId = firstNonBlank(
			span.getAttributes().get(AIRICRAFT_THREAD_ID),
			span.getAttributes().get(WANDB_THREAD_ID)
		);
		Boolean turn = span.getAttributes().get(AIRICRAFT_TURN);
		if (turn == null) {
			turn = span.getAttributes().get(WANDB_IS_TURN);
		}
		String inputValue = span.getAttributes().get(INPUT_VALUE);
		String outputValue = span.getAttributes().get(OUTPUT_VALUE);
		return "%s traceId=%s parent=%s thread=%s turn=%s inputLen=%d outputLen=%d".formatted(
			span.getName(),
			span.getTraceId(),
			span.getParentSpanId(),
			threadId,
			turn,
			inputValue == null ? 0 : inputValue.length(),
			outputValue == null ? 0 : outputValue.length()
		);
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second == null ? "" : second;
	}
}
