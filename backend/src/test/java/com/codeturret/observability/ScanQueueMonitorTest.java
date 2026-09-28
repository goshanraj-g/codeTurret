package com.codeturret.observability;

import com.codeturret.messaging.ProgressPublisher;
import com.codeturret.model.Scan;
import com.codeturret.model.ScanStatus;
import com.codeturret.repository.ScanRepo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScanQueueMonitorTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final ScanRepo scanRepo = mock(ScanRepo.class);
    private final ProgressPublisher progress = mock(ProgressPublisher.class);
    private final ScanQueueMonitor monitor = new ScanQueueMonitor(scanRepo, progress);

    @Test
    void healthyWhenNothingIsStuck() {
        when(scanRepo.findByStatusAndStartedAtBefore(any(), any())).thenReturn(List.of());

        assertThat(monitor.sweep(NOW)).isTrue();
        verify(scanRepo).countByStatusAndQueuedAtBefore(ScanStatus.QUEUED, NOW.minus(ScanQueueMonitor.MAX_QUEUED));
        verifyNoInteractions(progress);
    }

    @Test
    void scansWaitingInTheQueueAreUnhealthyButLeftQueued() {
        when(scanRepo.countByStatusAndQueuedAtBefore(any(), any())).thenReturn(2L);
        when(scanRepo.findByStatusAndStartedAtBefore(any(), any())).thenReturn(List.of());

        assertThat(monitor.sweep(NOW)).isFalse();
        verifyNoInteractions(progress);
    }

    @Test
    void hungScansAreMarkedFailedAndTheUserIsTold() {
        Scan hung = new Scan();
        hung.setId(UUID.randomUUID());
        hung.setStatus(ScanStatus.RUNNING);
        when(scanRepo.findByStatusAndStartedAtBefore(ScanStatus.RUNNING, NOW.minus(ScanQueueMonitor.MAX_RUNNING)))
            .thenReturn(List.of(hung));

        assertThat(monitor.sweep(NOW)).isFalse();
        assertThat(hung.getStatus()).isEqualTo(ScanStatus.FAILED);
        assertThat(hung.getCompletedAt()).isEqualTo(NOW);
        assertThat(hung.getErrorMessage()).contains("did not finish within 60 minutes");
        verify(progress).scanFailed(eq(hung.getId().toString()), any());
        verify(scanRepo).saveAll(List.of(hung));
    }
}
