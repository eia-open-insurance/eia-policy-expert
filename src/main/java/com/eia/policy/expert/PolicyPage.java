package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Page envelope for the policy list:
 * {@code {items, page, size, total, _links:{self,next,prev}}}.
 * {@code next} and {@code prev} are omitted at the respective boundary.
 */
public record PolicyPage(
        List<Policy> items,
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
