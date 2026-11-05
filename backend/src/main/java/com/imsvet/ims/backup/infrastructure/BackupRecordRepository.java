package com.imsvet.ims.backup.infrastructure;

import com.imsvet.ims.backup.domain.BackupRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface BackupRecordRepository extends Repository<BackupRecord, Long> {

    BackupRecord saveAndFlush(BackupRecord record);

    Optional<BackupRecord> findById(Long id);

    /** Newest first. */
    Page<BackupRecord> findAllByOrderByStartedAtDescIdDesc(Pageable pageable);

    /** Successful backups whose file is still kept, newest first (ADR-145). */
    @Query("""
            SELECT b FROM BackupRecord b WHERE b.status = 'success' AND b.removedAt IS NULL
            ORDER BY b.finishedAt DESC, b.id DESC""")
    List<BackupRecord> kept();

    /** Whether the daily job already ran (success or failure) since {@code since} (ADR-145: once per business day). */
    @Query("SELECT COUNT(b) > 0 FROM BackupRecord b WHERE b.triggerType = 'scheduled' AND b.startedAt >= :since")
    boolean scheduledRunSince(Instant since);

    Optional<BackupRecord> findFirstByOrderByStartedAtDescIdDesc();

    Optional<BackupRecord> findFirstByStatusOrderByFinishedAtDescIdDesc(String status);

    @Query("SELECT MAX(b.lastDownloadedAt) FROM BackupRecord b")
    Optional<Instant> lastDownload();
}
