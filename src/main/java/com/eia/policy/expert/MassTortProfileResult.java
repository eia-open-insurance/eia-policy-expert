package com.eia.policy.expert;

/**
 * Outcome of a profile upsert: the stored profile plus whether the row was
 * created ({@code 201}) or updated ({@code 200}).
 */
public record MassTortProfileResult(PolicyMassTortProfile profile, boolean created) {
}
