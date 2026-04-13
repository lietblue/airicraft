package ai.moeru.airicraft.agent.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

final class FilteringSpanExporter implements SpanExporter {
	private final SpanExporter delegate;
	private final Predicate<SpanData> includeSpan;

	FilteringSpanExporter(SpanExporter delegate, Predicate<SpanData> includeSpan) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
		this.includeSpan = Objects.requireNonNull(includeSpan, "includeSpan");
	}

	@Override
	public CompletableResultCode export(Collection<SpanData> spans) {
		List<SpanData> filtered = spans.stream()
			.filter(includeSpan)
			.toList();
		if (filtered.isEmpty()) {
			return CompletableResultCode.ofSuccess();
		}
		return delegate.export(filtered);
	}

	@Override
	public CompletableResultCode flush() {
		return delegate.flush();
	}

	@Override
	public CompletableResultCode shutdown() {
		return delegate.shutdown();
	}
}
