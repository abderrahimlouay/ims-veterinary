package com.imsvet.ims.audit.application;

import com.imsvet.ims.audit.application.UserDirectory.UserName;
import com.imsvet.ims.audit.domain.AuditEntry;
import com.imsvet.ims.audit.infrastructure.AuditEntryRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.time.BusinessSettings;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Searches the audit log, newest first (permission {@code audit.read}, security.md §4).
 */
@Service
public class AuditLogQuery {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() {
    };

    private final AuditEntryRepository repository;
    private final BusinessSettings business;
    private final JsonMapper json;
    private final UserDirectory users;

    public AuditLogQuery(AuditEntryRepository repository, BusinessSettings business, JsonMapper json,
            UserDirectory users) {
        this.repository = repository;
        this.business = business;
        this.json = json;
        this.users = users;
    }

    @PreAuthorize("hasAuthority('audit.read')")
    @Transactional(readOnly = true)
    public PageResponse<AuditEntryView> search(AuditLogFilter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "'from' must not be after 'to'.",
                    Map.of("from", filter.from().toString(), "to", filter.to().toString()));
        }
        LocalDate dayAfterTo = filter.to() == null ? null : filter.to().plusDays(1);
        Page<AuditEntry> entries = repository.search(filter.entity(), filter.entityId(), filter.userId(),
                filter.action(), startOf(filter.from()), startOf(dayAfterTo), PageRequests.of(page, size, NEWEST_FIRST));
        Map<Long, UserName> names = users.namesOf(entries.stream().map(AuditEntry::userId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return PageResponse.of(entries, e -> toView(e, e.userId() == null ? null : names.get(e.userId())));
    }

    private Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(business.timeZone()).toInstant();
    }

    private AuditEntryView toView(AuditEntry e, UserName name) {
        return new AuditEntryView(e.id(), e.userId(), name == null ? null : name.username(),
                name == null ? null : name.fullName(), e.action(), e.entity(), e.entityId(), fromJson(e.oldValues()),
                fromJson(e.newValues()), e.ipAddress(), e.createdAt());
    }

    private Map<String, Object> fromJson(String values) {
        return values == null ? null : json.readValue(values, VALUES);
    }
}
