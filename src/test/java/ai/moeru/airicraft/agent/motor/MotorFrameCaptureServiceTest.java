package ai.moeru.airicraft.agent.motor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotorFrameCaptureServiceTest {
	@Test
	void permitsOneCaptureAndKeepsIdsMonotonicAcrossFailureAndCancellation() {
		MotorFrameCaptureService service = new MotorFrameCaptureService(Runnable::run, () -> 100L);
		CompletableFuture<MotorFrame> first = service.requestCapture();

		assertEquals(new MotorFrameCaptureSnapshot(true, 1L, "pending"), service.snapshot());
		assertThrows(IllegalStateException.class, service::requestCapture);

		RuntimeException failure = new RuntimeException("world left");
		assertTrue(service.failActiveCapture(failure));
		CompletionException completion = assertThrows(CompletionException.class, first::join);
		assertSame(failure, completion.getCause());
		assertEquals(MotorFrameCaptureSnapshot.idle(), service.snapshot());

		CompletableFuture<MotorFrame> second = service.requestCapture();
		assertEquals(2L, service.snapshot().frameId());
		assertTrue(service.cancelActiveCapture());
		assertTrue(second.isCancelled());
		assertEquals(MotorFrameCaptureSnapshot.idle(), service.snapshot());

		CompletableFuture<MotorFrame> third = service.requestCapture();
		assertEquals(3L, service.snapshot().frameId());
		third.cancel(false);
		assertFalse(service.snapshot().active());
		assertFalse(service.cancelActiveCapture());
	}
}
