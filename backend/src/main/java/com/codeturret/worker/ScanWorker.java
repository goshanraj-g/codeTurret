package com.codeturret.worker;

import com.codeturret.config.LlmProperties;
import com.codeturret.engine.DetectionEngine;
import com.codeturret.engine.model.GitSignals;
import com.codeturret.engine.verify.EngineFinding;
import com.codeturret.messaging.ProgressPublisher;
import com.codeturret.messaging.RabbitConfig;
import com.codeturret.messaging.ScanJobMessage;
import com.codeturret.model.*;
import com.codeturret.repository.FindingRepo;
import com.codeturret.repository.ScanRepo;
import com.codeturret.service.GitService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.ISentryLifecycleToken;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Runs a scan job: clone, run the {@link DetectionEngine}, persist findings file by file as they are verified.
 *
 * <p>Deliberately not {@code @Transactional}: a scan takes minutes, and each file's findings are committed as soon
 * as they are verified, so a failure late in the scan keeps everything found up to that point.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScanWorker {

    private final ScanRepo scanRepo;
    private final FindingRepo findingRepo;
    private final GitService gitService;
    private final DetectionEngine engine;
    private final ProgressPublisher progress;
    private final ObjectMapper objectMapper;
    private final LlmProperties llm;

    @RabbitListener(queues = RabbitConfig.SCAN_QUEUE)
    public void handleScanJob(ScanJobMessage msg) {
        // Tag everything Sentry records during this job, so an error links back to its scan row.
        try (ISentryLifecycleToken ignored = Sentry.pushIsolationScope()) {
            Sentry.setTag("scan.id", msg.getScanId());
            Sentry.setTag("job", "scan");
            Sentry.setTag("llm.provider", llm.getProvider());
            Sentry.setTag("deep_scan", String.valueOf(msg.isDeepScan()));
            runScan(msg);
        }
    }

    private void runScan(ScanJobMessage msg) {
        String scanId = msg.getScanId();
        log.info("Starting scan job: {}", scanId);

        Scan scan = scanRepo.findWithRepositoryById(UUID.fromString(scanId)).orElse(null);
        if (scan == null) {
            log.error("Scan not found: {}", scanId);
            return;
        }

        Repository repo = scan.getRepository();
        Path repoDir = null;
        int[] findingsSaved = {0};

        try {
            scan.setStatus(ScanStatus.RUNNING);
            scan.setStartedAt(Instant.now());
            scanRepo.save(scan);

            log.info("Cloning {}", repo.getUrl());
            repoDir = gitService.cloneRepo(repo.getUrl());
            Path dir = repoDir;

            GitSignals git = new GitSignals(gitService.getHotFiles(dir), gitService.getSecurityCommits(dir));
            String repoContext = gitService.getRepoContext(dir);

            DetectionEngine.Result result = engine.run(dir, git, repoContext, msg.isDeepScan(), new DetectionEngine.Listener() {
                @Override
                public void started(int filesToVerify, int unitsSelected) {
                    progress.scanStarted(scanId, filesToVerify);
                }

                @Override
                public void fileVerified(String file, List<EngineFinding> findings) {
                    List<Finding> entities = findings.stream().map(f -> toEntity(f, scan, repo, dir)).toList();
                    findingRepo.saveAll(entities);
                    findingsSaved[0] += entities.size();
                    progress.fileScanned(scanId, file, findings.size(), topSeverity(findings));
                }
            });

            scan.setStatus(ScanStatus.COMPLETED);
            scan.setCompletedAt(Instant.now());
            scan.setTotalFiles(result.filesAnalyzed());
            scan.setFindingsCount(findingsSaved[0]);
            scan.setUnitsAnalyzed(result.unitsAnalyzed());
            scan.setLinesAnalyzed(result.linesAnalyzed());
            scan.setStaticHits(result.staticHits());
            scanRepo.save(scan);

            progress.scanComplete(scanId, findingsSaved[0]);
            log.info("Scan {} complete: {} findings from {} units ({} lines) in {} files", scanId, findingsSaved[0],
                result.unitsAnalyzed(), result.linesAnalyzed(), result.filesAnalyzed());

        } catch (Exception e) {
            log.error("Scan {} failed: {}", scanId, e.getMessage(), e);
            scan.setStatus(ScanStatus.FAILED);
            scan.setCompletedAt(Instant.now());
            scan.setFindingsCount(findingsSaved[0]);
            scan.setErrorMessage(e.getMessage());
            scanRepo.save(scan);
            progress.scanFailed(scanId, e.getMessage());
        } finally {
            if (repoDir != null) gitService.cleanup(repoDir);
        }
    }

    private Finding toEntity(EngineFinding ef, Scan scan, Repository repo, Path repoDir) {
        Finding f = new Finding();
        f.setScan(scan);
        f.setRepository(repo);
        f.setFilePath(ef.file());
        f.setLineNumber(ef.lineNumber());
        f.setSeverity(parseSeverity(ef.severity()));
        f.setVulnType(ef.vulnType());
        f.setDescription(ef.description());
        f.setFixSuggestion(ef.fixSuggestion());
        f.setCodeSnippet(ef.codeSnippet());
        f.setModelUsed(ef.modelUsed());
        f.setConfidence(ef.confidence());
        f.setSource(ef.source().name());
        f.setCweId(ef.cweId());
        f.setMlScore(ef.mlScore());
        f.setSignals(toJson(ef.signals()));

        if (ef.lineNumber() != null && ef.lineNumber() > 0) {
            GitService.BlameInfo blame = gitService.blameLine(repoDir, ef.file(), ef.lineNumber());
            if (blame != null) {
                f.setCommitHash(blame.hash());
                f.setCommitAuthor(blame.author());
                if (!blame.date().isBlank()) f.setCommitDate(Instant.parse(blame.date() + "T00:00:00Z"));
            }
        }
        return f;
    }

    private String toJson(Map<String, Double> signals) {
        try {
            return objectMapper.writeValueAsString(signals);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private static String topSeverity(List<EngineFinding> findings) {
        return findings.stream().map(EngineFinding::severity)
            .min(Comparator.comparingInt(s -> SEVERITY_ORDER.contains(s) ? SEVERITY_ORDER.indexOf(s) : SEVERITY_ORDER.size()))
            .orElse(null);
    }

    private Severity parseSeverity(String s) {
        try { return Severity.valueOf(s.toUpperCase()); } catch (Exception e) { return Severity.MEDIUM; }
    }
}
