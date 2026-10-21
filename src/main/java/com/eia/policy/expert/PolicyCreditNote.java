package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A billing credit note for a policy (SPEC 11.2.13). A note starts DRAFT, then
 * advances once through the lifecycle DRAFT -&gt; ISSUED -&gt; APPLIED, with
 * CANCELLED reachable from DRAFT or ISSUED. APPLIED and CANCELLED are terminal.
 *
 * <p>{@code issuedAt} is stamped by the ISSUED transition and preserved when the
 * note is later applied or cancelled. {@code appliedAt} is stamped only by the
 * APPLIED transition, so a cancelled note never invents a timestamp.</p>
 */
public record PolicyCreditNote(
        UUID id,
        UUID tenantId,
        UUID policyId,
        String noteNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String reason,
        String status,
        Instant issuedAt,
        Instant appliedAt,
        Instant createdAt
) {
}
