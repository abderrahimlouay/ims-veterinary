package com.imsvet.ims.backup.api;

import com.imsvet.ims.backup.application.Backups;
import com.imsvet.ims.backup.application.Backups.BackupFile;
import com.imsvet.ims.backup.application.Backups.BackupStatus;
import com.imsvet.ims.backup.application.Backups.BackupView;
import com.imsvet.ims.common.paging.PageResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** api.md §4.14 {@code /api/backups} (ADR-144…146); permissions on the service. */
@RestController
@RequestMapping("/api/backups")
class BackupController {

    private static final MediaType GZIP = MediaType.parseMediaType("application/gzip");

    private final Backups backups;

    BackupController(Backups backups) {
        this.backups = backups;
    }

    @GetMapping
    PageResponse<BackupView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return backups.list(page, size);
    }

    @GetMapping("/status")
    BackupStatus status() {
        return backups.status();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BackupView run() {
        return backups.runNow();
    }

    @GetMapping("/{id}/download")
    ResponseEntity<InputStreamResource> download(@PathVariable long id) {
        BackupFile file = backups.download(id);
        return ResponseEntity.ok().contentType(GZIP).contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName())
                        .build().toString())
                .cacheControl(CacheControl.noStore()).body(new InputStreamResource(file.content()));
    }
}
