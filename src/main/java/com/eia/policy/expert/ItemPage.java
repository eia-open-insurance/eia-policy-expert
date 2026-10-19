package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Page envelope for policy sub-resource collections:
 * {@code {items, page, size, total, _links:{self,next,prev}}}.
 */
public record ItemPage<T>(
        List<T> items,
        int page,
        int size,
        long total,
        @JsonProperty("_links") Links _links
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Links(Link self, Link next, Link prev) {
    }

    public record Link(String href) {
    }
}
