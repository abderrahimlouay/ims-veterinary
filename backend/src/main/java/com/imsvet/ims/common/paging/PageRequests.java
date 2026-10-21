package com.imsvet.ims.common.paging;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Page bounds of api.md §1: page ≥ 0, 1 ≤ size ≤ 100; violations are {@code VALIDATION_FAILED}.
 */
public final class PageRequests {

    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    public static PageRequest of(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Page must be ≥ 0 and size between 1 and " + MAX_SIZE + ".",
                    Map.of("page", page, "size", size));
        }
        return PageRequest.of(page, size, sort);
    }
}
