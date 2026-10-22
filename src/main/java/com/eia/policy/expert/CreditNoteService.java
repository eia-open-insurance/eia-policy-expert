package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Policy credit note slice (SPEC 11.2.13 billing adjustments).
 *
 * <p>A note is created DRAFT for the policy's premium currency. The only
 * mutations are the lifecycle transitions DRAFT -&gt; ISSUED -&gt; APPLIED and
 * DRAFT/ISSUED -&gt; CANCELLED plus APPLIED -&gt; REVERSED; APPLIED, CANCELLED,
 * and REVERSED are terminal. Applying a note requires an open (DUE/OVERDUE)
 * installment on the same tenant and policy; the note amount is credited to the
 * installment, which becomes CREDITED when the credits cover its full amount.
 * Reversing an APPLIED note releases the note amount from the installment it
 * was applied to, reopening a fully CREDITED installment as DUE/OVERDUE, and
 * requires a reason. The row changes, the policy version bump, and the matching
 * outbox event ({@code policy.credit-note.issued},
 * {@code policy.credit-note.applied}, {@code policy.credit-note.cancelled}, or
 * {@code policy.credit-note.reversed}) commit in one transaction, so a failed
 * application or reversal cannot leave a half-credited installment behind.</p>
 */
@Service
public class CreditNoteService {
    private final PolicyRepository policyRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final CancellationRepository cancellationRepository;
    private final BillingRepository billingRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public CreditNoteService(PolicyRepository policyRepository,
                             CreditNoteRepository creditNoteRepository,
                             CancellationRepository cancellationRepository,
                             BillingRepository billingRepository,
                             OutboxRepository outbox) {
        this(policyRepository, creditNoteRepository, cancellationRepository, billingRepository,
                outbox, Clock.systemUTC());
    }

    CreditNoteService(PolicyRepository policyRepository,
                      CreditNoteRepository creditNoteRepository,
                      CancellationRepository cancellationRepository,
                      BillingRepository billingRepository,
                      OutboxRepository outbox, Clock clock) {
        this.policyRepository = policyRepository;
        this.creditNoteRepository = creditNoteRepository;
        this.cancellationRepository = cancellationRepository;
        this.billingRepository = billingRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Lists the policy's credit notes, newest first. */
    public List<PolicyCreditNote> list(UUID tenantId, UUID policyId) {
        requirePolicy(tenantId, policyId);
        return creditNoteRepository.findAll(tenantId, policyId);
    }

    /**
     * Creates a DRAFT credit note. Validation runs before the note number is
     * consumed, so a rejected body never burns a number.
     *
     * <p>When the request names a {@code cancellationId}, the cancellation is
     * resolved on the same tenant and policy (otherwise 404), must still be
     * ACTIVE (otherwise 409), and must match the note amount within one cent and
     * the note currency exactly (otherwise 400). A cancellation can fund at most
     * one note: a duplicate is 409 {@code DUPLICATE_CANCELLATION_CREDIT_NOTE},
     * and the unique key on {@code (tenant_id, policy_id, cancellation_id)} also
     * arbitrates a concurrent duplicate. The accepted create writes
     * {@code policy.credit-note.created} in the same transaction as the note row
     * and the policy-version bump; the event carries the cancellation identity
     * when the note is linked.</p>
     */
    @Transactional
    public PolicyCreditNote create(UUID tenantId, UUID policyId,
                                   CreateCreditNoteRequest request) {
        Policy policy = requirePolicy(tenantId, policyId);
        CreditNoteRules.CreditNoteSpec spec = CreditNoteRules.validateCreate(request, policy);
        UUID cancellationId = request.cancellationId();
        PolicyCancellation cancellation = null;
        if (cancellationId != null) {
            cancellation = cancellationRepository.findCancellation(tenantId, policyId,
                            cancellationId)
                    .orElseThrow(() -> cancellationNotFound(cancellationId));
            creditNoteRepository.findByCancellation(tenantId, policyId, cancellationId)
                    .ifPresent(existing -> {
                        throw duplicateCancellation(cancellationId, existing);
                    });
            CreditNoteRules.validateCancellationLink(spec, cancellation);
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        PolicyCreditNote creditNote = new PolicyCreditNote(UUID.randomUUID(), tenantId, policyId,
                creditNoteRepository.nextNoteNumber(), spec.amount(), spec.currency(),
                spec.reason(), CreditNoteRules.DRAFT, null, null, null, now, null, null,
                cancellationId, cancellationNumber(cancellation));
        try {
            creditNoteRepository.insert(creditNote);
        } catch (DuplicateKeyException ex) {
            if (cancellationId != null) {
                throw duplicateCancellation(cancellationId, null);
            }
            throw ex;
        }
        Policy touched = touch(policy);
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.creditNoteCreated(touched, creditNote, now)));
        return creditNote;
    }

    /**
     * Advances a credit note once. The conditional update guards the expected
     * status; a concurrent winner (or a repeated terminal target) is a 409. The
     * ISSUED transition stamps {@code issuedAt}, APPLIED stamps
     * {@code appliedAt} and {@code appliedInstallmentId}, and CANCELLED stamps
     * neither while preserving any timestamp the note already carries.
     *
     * <p>APPLIED is the only transition that reads an installment from the
     * request. The installment must be DUE/OVERDUE on this tenant and policy,
     * and the note amount must fit in its uncredited remainder. The installment
     * update is conditional on that remainder, so a concurrent credit can never
     * overdraw it; the note transition rolls back with the failed installment
     * update.</p>
     *
     * <p>REVERSED is only reachable from APPLIED and requires a reason. It
     * releases the note amount from the installment recorded by the
     * application, keeps {@code appliedInstallmentId} for audit, stamps
     * {@code reversedAt}/{@code reversalReason}, and reopens a CREDITED
     * installment as DUE/OVERDUE when it no longer covers its gross amount. A
     * legacy APPLIED note without an installment is simply marked REVERSED.</p>
     */
    @Transactional
    public PolicyCreditNote updateStatus(UUID tenantId, UUID policyId, UUID creditNoteId,
                                         UpdateCreditNoteStatusRequest request) {
        Policy policy = requirePolicy(tenantId, policyId);
        PolicyCreditNote current = requireCreditNote(tenantId, policyId, creditNoteId);
        String target = CreditNoteRules.normalizeTargetStatus(
                request == null ? null : request.status());
        if (!CreditNoteRules.canTransition(current.status(), target)) {
            throw transitionConflict(current, target);
        }
        if (CreditNoteRules.REVERSED.equals(target)) {
            return reverse(tenantId, policyId, creditNoteId, policy, current, request);
        }

        PolicyInstallment installment = null;
        if (CreditNoteRules.APPLIED.equals(target)) {
            UUID installmentId = CreditNoteRules.requireInstallmentId(request.installmentId());
            installment = billingRepository.findInstallment(tenantId, policyId, installmentId)
                    .orElseThrow(() -> installmentNotFound(installmentId));
            CreditNoteRules.validateApply(current, installment);
        }

        // Freeze the validated target row for the lambdas below.
        PolicyInstallment creditInstallment = installment;
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Instant issuedAt = CreditNoteRules.ISSUED.equals(target) ? now : current.issuedAt();
        Instant appliedAt = CreditNoteRules.APPLIED.equals(target) ? now : current.appliedAt();
        UUID appliedInstallmentId = creditInstallment == null
                ? current.appliedInstallmentId() : creditInstallment.id();
        int rows = creditNoteRepository.updateStatus(tenantId, policyId, creditNoteId, target,
                issuedAt, appliedAt, appliedInstallmentId, current.status());
        if (rows == 0) {
            throw transitionConflict(requireCreditNote(tenantId, policyId, creditNoteId), target);
        }

        if (creditInstallment != null) {
            int credited = billingRepository.applyCredit(tenantId, creditInstallment.planId(),
                    creditInstallment.id(), current.amount(), creditInstallment.status());
            if (credited == 0) {
                // The state moved under us: re-run the invariants against the
                // current row to report the precise conflict, otherwise fall
                // back to the generic concurrency error. The note update above
                // rolls back either way.
                PolicyInstallment raced = billingRepository
                        .findInstallment(tenantId, policyId, creditInstallment.id())
                        .orElseThrow(() -> installmentNotFound(creditInstallment.id()));
                CreditNoteRules.validateApply(current, raced);
                throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "installmentId",
                        "The installment changed concurrently; reload and retry.");
            }
        }

        Policy touched = touch(policy);
        PolicyCreditNote updated = requireCreditNote(tenantId, policyId, creditNoteId);
        PolicyInstallment updatedInstallment = creditInstallment == null ? null
                : billingRepository.findInstallment(tenantId, policyId, creditInstallment.id())
                        .orElseThrow(() -> installmentNotFound(creditInstallment.id()));
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.creditNoteStatusChanged(touched, updated, updatedInstallment, now)));
        return updated;
    }

    /**
     * Reverses an APPLIED note in one transaction. The note row is reversed
     * conditionally on its APPLIED status first, then the credited installment
     * read at that moment is released conditionally on its credited amount and
     * status; either update failing rolls the other back. The reversal stamp
     * and reason are written by the note update, and the original application
     * facts (issued/applied timestamps and applied installment id) are preserved.
     */
    private PolicyCreditNote reverse(UUID tenantId, UUID policyId, UUID creditNoteId,
                                     Policy policy, PolicyCreditNote current,
                                     UpdateCreditNoteStatusRequest request) {
        String reversalReason = CreditNoteRules.normalizeReversalReason(
                request == null ? null : request.reason());

        PolicyInstallment creditedInstallment = null;
        BigDecimal restoredCreditedAmount = null;
        String restoredStatus = null;
        if (current.appliedInstallmentId() != null) {
            creditedInstallment = billingRepository.findInstallment(tenantId, policyId,
                            current.appliedInstallmentId())
                    .orElseThrow(() -> installmentNotFound(current.appliedInstallmentId()));
            restoredCreditedAmount = CreditNoteRules.creditedAfterReversal(
                    creditedInstallment, current.amount());
            restoredStatus = CreditNoteRules.statusAfterReversal(creditedInstallment,
                    current.amount(), LocalDate.now(clock));
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int rows = creditNoteRepository.reverse(tenantId, policyId, creditNoteId, now,
                reversalReason, current.status());
        if (rows == 0) {
            throw transitionConflict(requireCreditNote(tenantId, policyId, creditNoteId),
                    CreditNoteRules.REVERSED);
        }

        if (creditedInstallment != null) {
            int released = billingRepository.releaseCredit(tenantId,
                    creditedInstallment.planId(), creditedInstallment.id(),
                    restoredCreditedAmount, restoredStatus,
                    creditedInstallment.creditedAmount(), creditedInstallment.status());
            if (released == 0) {
                throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "installmentId",
                        "The installment changed concurrently; reload and retry.");
            }
        }

        Policy touched = touch(policy);
        PolicyCreditNote updated = requireCreditNote(tenantId, policyId, creditNoteId);
        outbox.insert(OutboxEvent.fromEnvelope(PolicyEvents.creditNoteReversed(touched, updated,
                creditedInstallment == null ? null : creditedInstallment.id(), now)));
        return updated;
    }

    private Policy requirePolicy(UUID tenantId, UUID policyId) {
        return policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
    }

    private PolicyCreditNote requireCreditNote(UUID tenantId, UUID policyId,
                                               UUID creditNoteId) {
        return creditNoteRepository.findById(tenantId, policyId, creditNoteId)
                .orElseThrow(() -> PolicyErrors.notFound("CREDIT_NOTE_NOT_FOUND",
                        "creditNoteId", "Credit note not found: " + creditNoteId));
    }

    private static ApiException installmentNotFound(UUID installmentId) {
        return PolicyErrors.notFound("INSTALLMENT_NOT_FOUND", "installmentId",
                "Installment not found: " + installmentId);
    }

    /** A cancellation from another tenant or policy is indistinguishable from a missing one. */
    private static ApiException cancellationNotFound(UUID cancellationId) {
        return PolicyErrors.notFound("CANCELLATION_NOT_FOUND", "cancellationId",
                "Cancellation not found: " + cancellationId);
    }

    /**
     * The cancellation business number, falling back to the row id for a
     * historical cancellation that predates numbering. The read join applies the
     * same fallback, so create, list, and the created event agree.
     */
    private static String cancellationNumber(PolicyCancellation cancellation) {
        if (cancellation == null) {
            return null;
        }
        return cancellation.cancellationNumber() == null
                ? cancellation.id().toString() : cancellation.cancellationNumber();
    }

    private static ApiException duplicateCancellation(UUID cancellationId,
                                                      PolicyCreditNote existing) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cancellationId", cancellationId.toString());
        if (existing != null) {
            meta.put("creditNoteId", existing.id().toString());
            meta.put("noteNumber", existing.noteNumber());
        }
        return PolicyErrors.conflict("DUPLICATE_CANCELLATION_CREDIT_NOTE", "cancellationId",
                "A credit note already exists for cancellation " + cancellationId + ".", meta);
    }

    private Policy touch(Policy policy) {
        int rows = policyRepository.touch(policy.tenantId(), policy.id(), policy.version());
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "policyId",
                    "The policy changed concurrently; reload and retry.");
        }
        return policyRepository.findById(policy.tenantId(), policy.id())
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policy.id()));
    }

    private static ApiException transitionConflict(PolicyCreditNote creditNote, String target) {
        return PolicyErrors.conflict("INVALID_CREDIT_NOTE_TRANSITION", "status",
                "Credit note " + creditNote.id() + " is " + creditNote.status()
                        + " and cannot transition to " + target + ".",
                Map.of("creditNoteId", creditNote.id().toString(),
                        "currentStatus", creditNote.status(),
                        "targetStatus", target));
    }
}
