package com.eia.policy.expert;

import java.util.List;

/** One page of policies and the tenant-wide total. */
public record PolicyPageData(List<Policy> items, long total) {
}
