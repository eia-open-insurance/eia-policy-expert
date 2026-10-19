package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Strong entity tags derived from the aggregate {@code version}. Version
 * {@code 3} is exposed as {@code ETag: "3"}.
 */
final class EntityTags {
    /** Sentinel for {@code If-Match: *}, meaning "any current representation". */
    static final int ANY = -1;

    private EntityTags() {
    }

    static String format(int version) {
        return "\"" + version + "\"";
    }

    static Integer parse(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.isEmpty()) {
            throw invalid("If-Match must be a policy ETag such as \"3\" or *.");
        }
        if ("*".equals(value)) {
            return ANY;
        }
        String first = value.split(",", 2)[0].trim();
        if (first.startsWith("W/")) {
            first = first.substring(2).trim();
        }
        if (first.length() >= 2 && first.startsWith("\"") && first.endsWith("\"")) {
            first = first.substring(1, first.length() - 1);
        }
        try {
            int version = Integer.parseInt(first);
            if (version < 0) {
                throw invalid("If-Match version must be zero or greater.");
            }
            return version;
        } catch (NumberFormatException ex) {
            throw invalid("If-Match must be a policy ETag such as \"3\" or *.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH", message,
                List.of(new ApiException.FieldIssue("If-Match", "INVALID_IF_MATCH", message)));
    }
}
