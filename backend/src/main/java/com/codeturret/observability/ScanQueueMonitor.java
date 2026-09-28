package com.codeturret.observability;

import com.codeturret.messaging.ProgressPublisher;
import com.codeturret.model.Scan;
import com.codeturret.model.ScanStatus;
import com.codeturret.repository.ScanRepo;
import io.sentry.CheckIn;
import io.sentry.CheckInStatus;
import io.sentry.MonitorConfig;
import io.sentry.MonitorSchedule;
import io.sentry.MonitorScheduleUnit;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Heartbeat for the scan pipeline, reported to Sentry Crons as the {@value #SLUG} monitor.
 *
 * <p>A stalled worker doesn't throw anything, so no error is ever reported: scans just wait forever. Every five
 * minutes this checks in with Sentry. The check-in is ERROR when a scan has waited in the queue for more than
 * {@link #MAX_QUEUED} (nothing is consuming it) or has been running for more than {@link #MAX_RUNNING} (the worker
 * died or hung mid-scan). If the whole app is down, the check-in is missed and Sentry alerts on that instead.
 *
 * <p>Scans that have been running too long are also marked FAILED, so the user stops waiting on a spinner and the
 * monitor recovers. Queued scans are left alone: they still run if the worker comes back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScanQueueMonitor {

    static final String SLUG = "scan-worker";
    static final Duration MAX_QUEUED = Duration.ofMinutes(15);
    static final Duration MAX_RUNNING = Duration.ofMinutes(60);

    private static final MonitorConfig MONITOR = monitorConfig();

    private final ScanRepo scanRepo;
    private final ProgressPublisher progress;

    @Scheduled(fixedRate = 5, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void check() {
        CheckIn checkIn = new CheckIn(SLUG, sweep(Instant.now()) ? CheckInStatus.OK : CheckInStatus.ERROR);
        checkIn.setMonitorConfig(MONITOR);
        Sentry.captureCheckIn(checkIn);
    }

    /** True if the queue is healthy. Marks scans running longer than {@link #MAX_RUNNING} as FAILED. */
    boolean sweep(Instant now) {
        long waiting = scanRepo.countByStatusAndQueuedAtBefore(ScanStatus.QUEUED, now.minus(MAX_QUEUED));
        List<Scan> hung = scanRepo.findByStatusAndStartedAtBefore(ScanStatus.RUNNING, now.minus(MAX_RUNNING));

        String error = "Scan did not finish within " + MAX_RUNNING.toMinutes() + " minutes; the worker may have stopped";
        for (Scan scan : hung) {
            scan.setStatus(ScanStatus.FAILED);
            scan.setCompletedAt(now);
            scan.setErrorMessage(error);
            progress.scanFailed(scan.getId().toString(), error);
        }
        scanRepo.saveAll(hung);

        if (waiting == 0 && hung.isEmpty()) return true;
        log.warn("Scan queue unhealthy: {} scan(s) queued over {} min, {} scan(s) running over {} min (marked FAILED)",
            waiting, MAX_QUEUED.toMinutes(), hung.size(), MAX_RUNNING.toMinutes());
        return false;
    }

    private static MonitorConfig monitorConfig() {
        MonitorConfig config = new MonitorConfig(MonitorSchedule.interval(5, MonitorScheduleUnit.MINUTE));
        config.setCheckinMargin(5L);
        return config;
    }
}
