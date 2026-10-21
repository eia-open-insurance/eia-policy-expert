package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Policy credit note slice (SPEC 11.2.13 billing adjustments).
 *
 * <p>A note is created DRAFT for the policy's premium currency. The only
 * mutations are the lifecycle transitions DRAFT -&gt; ISSUED -&gt; APPLIED and
 * DRAFT/ISSUED -&gt; CANCELLED; APPLIED and CANCELLED are terminal. The row
 * change, the policy version bump, and the matching outbox event
 * ({@code policy.credit-note.issued}, {@code policy.credit-note.applied}, or
 * {@code policy.credit-note.cancelled}) commit in one transaction.</p>
 */
@Service
public class CreditNoteService {
    private final PolicyRepository policyRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public CreditNoteService(PolicyRepository policyRepository,
                             CreditNoteRepository creditNoteRepository,
                             OutboxRepository outbox) {
        this(policyRepository, creditNoteRepository, outbox, Clock.systemUTC());
    }

    CreditNoteService(PolicyRepository policyRepository,
                      CreditNoteRepository creditNoteRepository,
                      OutboxRepository outbox, Clock clock) {
        this.policyRepository = policyRepository;
        this.creditNoteRepository = creditNoteRepository;
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
     * consumed, so a rejected body never burns a number. Creation itself is not
     * one of the lifecycle events: only ISSUED, APPLIED, and CANCELLED emit an
     * outbox event.
     */
    @Transactional
    public PolicyCreditNote create(UUID tenantId, UUID policyId,
                                   CreateCreditNoteRequest request) {
        Policy policy = requirePolicy(tenantId, policyId);
        CreditNoteRules.CreditNoteSpec spec = CreditNoteRules.validateCreate(request, policy);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        PolicyCreditNote creditNote = new PolicyCreditNote(UUID.randomUUID(), tenantId, policyId,
                creditNoteRepository.nextNoteNumber(), spec.amount(), spec.currency(),
                spec.reason(), CreditNoteRules.DRAFT, null, null, now);
        creditNoteRepository.insert(creditNote);
        touch(policy);
        return creditNote;
    }

    /**
     * Advances a credit note once. The conditional update guards the expected
     * status; a concurrent winner (or a repeated terminal target) is a 409. The
     * ISSUED transition stamps {@code issuedAt}, APPLIED stamps
     * {@code appliedAt}, and CANCELLED stamps neither while preserving any
     * timestamp the note already carries.
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

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Instant issuedAt = CreditNoteRules.ISSUED.equals(target) ? now : current.issuedAt();
        Instant appliedAt = CreditNoteRules.APPLIED.equals(target) ? now : current.appliedAt();
        int rows = creditNoteRepository.updateStatus(tenantId, policyId, creditNoteId, target,
                issuedAt, appliedAt, current.status());
        if (rows == 0) {
            throw transitionConflict(requireCreditNote(tenantId, policyId, creditNoteId), target);
        }

        Policy touched = touch(policy);
        PolicyCreditNote updated = requireCreditNote(tenantId, policyId, creditNoteId);
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.creditNoteStatusChanged(touched, updated, now)));
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
