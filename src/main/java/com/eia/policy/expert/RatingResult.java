package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of a rating command: the persisted worksheet and, when the caller
 * asked to apply it, the refreshed policy with the new premium and aggregate
 * version. A calculation-only request omits {@code policy}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RatingResult(PolicyRatingWorksheet worksheet, Policy policy) {
}
