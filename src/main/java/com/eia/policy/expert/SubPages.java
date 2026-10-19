package com.eia.policy.expert;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a page envelope for the small per-policy sub-resource collections.
 * Ordering is owned by the repository; the slice is stable because the source
 * lists are stable.
 */
final class SubPages {
    private SubPages() {
    }

    static <T> ItemPage<T> page(List<T> all, int page, int size, String basePath, UUID tenantId) {
        Pagination.validate(page, size);
        int from = page * size;
        List<T> items = from >= all.size()
                ? List.of()
                : List.copyOf(all.subList(from, Math.min(from + size, all.size())));
        return new ItemPage<>(items, page, size, all.size(),
                PageLinks.buildItem(basePath, Map.of("tenantId", tenantId.toString()),
                        page, size, all.size()));
    }
}
