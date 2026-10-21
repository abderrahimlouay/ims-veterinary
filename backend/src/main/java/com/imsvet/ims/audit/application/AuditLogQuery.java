package com.imsvet.ims.audit.application;

import com.imsvet.ims.audit.domain.AuditEntry;
import com.imsvet.ims.audit.infrastructure.AuditEntryRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.paging.PageRequests;
import com.imsvet.ims.common.paging.PageResponse;
import com.imsvet.ims.common.time.BusinessProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Searches the audit log, newest first. The permission check ({@code audit.read}) is added with the
 * endpoint once the security chain exists (ADR-036).
 */
@Service
public class AuditLogQuery {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() {
    };

    private final AuditEntryRepository repository;
    private final BusinessProperties business;
    private final JsonMapper json;

    public AuditLogQuery(AuditEntryRepository repository, BusinessProperties business, JsonMapper json) {
        this.repository = repository;
        this.business = business;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntryView> search(AuditLogFilter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "'from' must not be after 'to'.",
                    Map.of("from", filter.from().toString(), "to", filter.to().toString()));
        }
        LocalDate dayAfterTo = filter.to() == null ? null : filter.to().plusDays(1);
        return PageResponse.of(repository.search(filter.entity(), filter.entityId(), filter.userId(),
                filter.action(), startOf(filter.from()), startOf(dayAfterTo),
                PageRequests.of(page, size, NEWEST_FIRST)), this::toView);
    }

    private Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(business.timeZone()).toInstant();
    }

    private AuditEntryView toView(AuditEntry e) {
        return new AuditEntryView(e.id(), e.userId(), e.action(), e.entity(), e.entityId(), fromJson(e.oldValues()),
                fromJson(e.newValues()), e.ipAddress(), e.createdAt());
    }

    private Map<String, Object> fromJson(String values) {
        return values == null ? null : json.readValue(values, VALUES);
    }
}
