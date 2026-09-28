package com.codeturret.repository;

import com.codeturret.model.Scan;
import com.codeturret.model.ScanStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScanRepo extends JpaRepository<Scan, UUID> {

    @Query("SELECT s FROM Scan s JOIN FETCH s.repository ORDER BY s.startedAt DESC")
    List<Scan> findRecentScans(Pageable pageable);

    List<Scan> findByRepository_IdOrderByStartedAtDesc(UUID repoId);

    @Query("SELECT s FROM Scan s JOIN FETCH s.repository WHERE s.id = :id")
    Optional<Scan> findWithRepositoryById(UUID id);

    long countByStatusAndQueuedAtBefore(ScanStatus status, Instant cutoff);

    List<Scan> findByStatusAndStartedAtBefore(ScanStatus status, Instant cutoff);
}
