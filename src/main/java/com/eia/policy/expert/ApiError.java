package com.eia.policy.expert;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<ApiException.FieldIssue> details
) {}
