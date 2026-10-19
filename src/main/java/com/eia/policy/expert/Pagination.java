package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Cursor-free pagination rules: default size 20, maximum 100, page zero or
 * greater.
 */
final class Pagination {
    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private Pagination() {
    }

    static void validate(int page, int size) {
        List<ApiException.FieldIssue> issues = new ArrayList<>();
        if (page < 0) {
            issues.add(new ApiException.FieldIssue(
                    "page", "OUT_OF_RANGE", "page must be zero or greater"));
        }
        if (size < 1 || size > MAX_SIZE) {
            issues.add(new ApiException.FieldIssue(
                    "size", "OUT_OF_RANGE", "size must be between 1 and " + MAX_SIZE));
        }
        if (!issues.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Pagination parameters are invalid.", issues);
        }
    }
}
