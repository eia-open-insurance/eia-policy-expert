package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * One dunning action recorded against an installment (SPEC 11.2.13). A row is
 * written when a reminder or notice is sent; {@code status} is {@code SENT} for
 * a delivered action and {@code CANCELLED} only for superseded history. The
 * {@code (tenantId, installmentId, level)} triple is unique, so each escalation
 * level is sent at most once per installment.
 */
public record BillingDunningAction(
        UUID id,
        UUID tenantId,
        UUID installmentId,
        UUID planId,
        UUID policyId,
        String level,
        String channel,
        String notes,
        String status,
        Instant sentAt,
        Instant createdAt
) {
}
