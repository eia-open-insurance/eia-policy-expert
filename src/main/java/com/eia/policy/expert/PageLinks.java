package com.eia.policy.expert;

import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Builds the {@code _links} object for a page envelope. Expert links preserve
 * the required {@code tenantId} query parameter.
 */
final class PageLinks {
    private PageLinks() {
    }

    static PolicyPage.Links build(String basePath, Map<String, String> filters,
                                  int page, int size, long total) {
        PolicyPage.Link self = new PolicyPage.Link(href(basePath, filters, page, size));
        PolicyPage.Link next = (long) (page + 1) * size < total
                ? new PolicyPage.Link(href(basePath, filters, page + 1, size))
                : null;
        PolicyPage.Link prev = page > 0 && (long) page * size <= total
                ? new PolicyPage.Link(href(basePath, filters, page - 1, size))
                : null;
        return new PolicyPage.Links(self, next, prev);
    }

    static ItemPage.Links buildItem(String basePath, Map<String, String> filters,
                                    int page, int size, long total) {
        ItemPage.Link self = new ItemPage.Link(href(basePath, filters, page, size));
        ItemPage.Link next = (long) (page + 1) * size < total
                ? new ItemPage.Link(href(basePath, filters, page + 1, size))
                : null;
        ItemPage.Link prev = page > 0 && (long) page * size <= total
                ? new ItemPage.Link(href(basePath, filters, page - 1, size))
                : null;
        return new ItemPage.Links(self, next, prev);
    }

    static String href(String basePath, Map<String, String> filters, int page, int size) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(basePath);
        filters.forEach(builder::queryParam);
        builder.queryParam("page", page);
        builder.queryParam("size", size);
        return builder.build().toUriString();
    }
}
