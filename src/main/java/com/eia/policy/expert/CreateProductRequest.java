package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create body for the product catalog. The id, status, and timestamps are
 * server-owned. The short aliases keep the body compatible with clients that
 * use {@code code}/{@code name}/{@code line} instead of the table-shaped names.
 */
public record CreateProductRequest(
        @NotBlank @Size(max = 32) @JsonAlias("code") String productCode,
        @NotBlank @Size(max = 200) @JsonAlias("name") String productName,
        @NotBlank @JsonAlias("line") String productLine,
        @NotBlank @Size(max = 2) String jurisdiction
) {
}
