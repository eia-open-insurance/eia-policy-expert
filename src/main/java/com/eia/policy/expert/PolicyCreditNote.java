package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A billing credit note for a policy (SPEC 11.2.13). A note starts DRAFT, then
 * advances once through the lifecycle DRAFT -&gt; ISSUED -&gt; APPLIED, with
 * CANCELLED reachable from DRAFT or ISSUED and REVERSED reachable from APPLIED.
 * APPLIED, CANCELLED, and REVERSED are terminal.
 *
 * <p>{@code issuedAt} is stamped by the ISSUED transition and preserved when the
 * note is later applied, cancelled, or reversed. {@code appliedAt} is stamped
 * only by the APPLIED transition, so a cancelled note never invents a timestamp;
 * a REVERSED note keeps both stamps so the original application stays auditable.
 * {@code appliedInstallmentId} is the installment an APPLIED note was credited
 * against and is retained by a REVERSED note; it stays null for every other
 * status. {@code reversedAt} and {@code reversalReason} are written only by the
 * REVERSED transition.</p>
 *
 * <p>{@code cancellationId} links a return-premium note to the cancellation it
 * refunds; it is null for a general billing adjustment. {@code cancellationNumber}
 * is the linked cancellation's business reference and is null whenever the note
 * has no cancellation link.</p>
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
        UUID appliedInstallmentId,
        Instant createdAt,
        Instant reversedAt,
        String reversalReason,
        UUID cancellationId,
        String cancellationNumber
) {
    /** Legacy shape for a note that was not applied to a specific installment. */
    public PolicyCreditNote(UUID id, UUID tenantId, UUID policyId, String noteNumber,
                            BigDecimal amount, String currency, String reason, String status,
                            Instant issuedAt, Instant appliedAt, Instant createdAt) {
        this(id, tenantId, policyId, noteNumber, amount, currency, reason, status, issuedAt,
                appliedAt, null, createdAt, null, null, null, null);
    }

    /**
     * Compatibility shape with the applied installment appended after the
     * original members, matching projections that were widened without
     * reordering.
     */
    public PolicyCreditNote(UUID id, UUID tenantId, UUID policyId, String noteNumber,
                            BigDecimal amount, String currency, String reason, String status,
                            Instant issuedAt, Instant appliedAt, Instant createdAt,
                            UUID appliedInstallmentId) {
        this(id, tenantId, policyId, noteNumber, amount, currency, reason, status, issuedAt,
                appliedAt, appliedInstallmentId, createdAt, null, null, null, null);
    }

    /** Shape for a transition that names an installment but predates reversal. */
    public PolicyCreditNote(UUID id, UUID tenantId, UUID policyId, String noteNumber,
                            BigDecimal amount, String currency, String reason, String status,
                            Instant issuedAt, Instant appliedAt, UUID appliedInstallmentId,
                            Instant createdAt) {
        this(id, tenantId, policyId, noteNumber, amount, currency, reason, status, issuedAt,
                appliedAt, appliedInstallmentId, createdAt, null, null, null, null);
    }

    /** Shape for a transition that predates the cancellation link. */
    public PolicyCreditNote(UUID id, UUID tenantId, UUID policyId, String noteNumber,
                            BigDecimal amount, String currency, String reason, String status,
                            Instant issuedAt, Instant appliedAt, UUID appliedInstallmentId,
                            Instant createdAt, Instant reversedAt, String reversalReason) {
        this(id, tenantId, policyId, noteNumber, amount, currency, reason, status, issuedAt,
                appliedAt, appliedInstallmentId, createdAt, reversedAt, reversalReason, null, null);
    }

    /** Shape for a linked note whose cancellation number is read separately. */
    public PolicyCreditNote(UUID id, UUID tenantId, UUID policyId, String noteNumber,
                            BigDecimal amount, String currency, String reason, String status,
                            Instant issuedAt, Instant appliedAt, UUID appliedInstallmentId,
                            Instant createdAt, Instant reversedAt, String reversalReason,
                            UUID cancellationId) {
        this(id, tenantId, policyId, noteNumber, amount, currency, reason, status, issuedAt,
                appliedAt, appliedInstallmentId, createdAt, reversedAt, reversalReason,
                cancellationId, null);
    }
}
