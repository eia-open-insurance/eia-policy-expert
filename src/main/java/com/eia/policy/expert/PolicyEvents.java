package com.eia.policy.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;
import com.fasterxml.jackson.databind.node.NullNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Event factory for the Policy vertical.
 *
 * <p>Event names are the requested Policy slice names. Four of them are the
 * canonical names from {@code eia-contracts/asyncapi/policy.yaml} and SPEC
 * 11.2.9 ({@code version-issued}, {@code endorsed}, {@code cancelled},
 * {@code reinstated}); {@code created} and {@code updated} are not in that
 * catalog yet and are reported as contract mismatches in IMPLEMENTATION.md.</p>
 *
 * <p>The Policy aggregate is direct business, so every event carries
 * {@code book=DIRECT}. Rows start at version 0 for API compatibility while the
 * canonical envelope requires {@code aggregateVersion >= 1}; the event version
 * is therefore the row version plus one (create -> 1, first update -> 2).</p>
 *
 * <p>Money is emitted as a decimal string with scale 4, matching the platform
 * money contract (SPEC 10.3). Dates are ISO-8601 strings.</p>
 */
final class PolicyEvents {
    static final String CREATED = "policy.policy.created";
    static final String UPDATED = "policy.policy.updated";
    static final String VERSION_ISSUED = "policy.policy.version-issued";
    static final String ENDORSED = "policy.policy.endorsed";
    static final String PREMIUM_AUDITED = "policy.policy.premium-audited";
    static final String RATED = "policy.policy.rated";
    static final String CANCELLED = "policy.policy.cancelled";
    static final String REINSTATED = "policy.policy.reinstated";
    static final String RENEWED = "policy.policy.renewed";
    static final String BILLING_PLAN_CREATED = "policy.billing-plan.created";
    static final String BILLING_PLAN_ADJUSTED = "policy.billing-plan.adjusted";
    static final String INSTALLMENT_PAID = "policy.installment.paid";
    static final String INSTALLMENT_WAIVED = "policy.installment.waived";
    static final String DUNNING_SENT = "policy.dunning.sent";
    static final String CREDIT_NOTE_CREATED = "policy.credit-note.created";
    static final String CREDIT_NOTE_ISSUED = "policy.credit-note.issued";
    static final String CREDIT_NOTE_APPLIED = "policy.credit-note.applied";
    static final String CREDIT_NOTE_CANCELLED = "policy.credit-note.cancelled";
    static final String CREDIT_NOTE_REVERSED = "policy.credit-note.reversed";
    static final String MASS_TORT_PROFILE_UPDATED = "policy.mass-tort-profile.updated";
    static final String PROGRAM_CREATED = "policy.program.created";
    static final String PROGRAM_ASSIGNED = "policy.policy.program-assigned";
    static final String LEGAL_ENTITY_CREATED = "policy.legal-entity.created";
    static final String LEGAL_ENTITY_ASSIGNED = "policy.policy.legal-entity-assigned";
    static final String SUBMISSION_CREATED = "policy.submission.created";
    static final String SUBMISSION_STATUS_CHANGED = "policy.submission.status-changed";
    static final String SUBMISSION_CONVERTED = "policy.submission.converted";
    static final String SUBMISSION_REFERRED = "policy.submission.referred";
    static final String SUBMISSION_REFERRAL_DECIDED = "policy.submission.referral-decided";

    private static final Book EVENT_BOOK = Book.DIRECT;
    private static final String VERTICAL = "policy";
    private static final String AGGREGATE_TYPE = "Policy";
    private static final String SUBJECT_TYPE = "policy";
    private static final String PROGRAM_AGGREGATE_TYPE = "Program";
    private static final String PROGRAM_SUBJECT_TYPE = "program";
    private static final String LEGAL_ENTITY_AGGREGATE_TYPE = "LegalEntity";
    private static final String LEGAL_ENTITY_SUBJECT_TYPE = "legal-entity";
    private static final String SUBMISSION_AGGREGATE_TYPE = "UnderwritingSubmission";
    private static final String SUBMISSION_SUBJECT_TYPE = "underwriting-submission";
    private static final String SOURCE_BUILD = "eia-policy-expert@0.1.0-SNAPSHOT";

    private PolicyEvents() {
    }

    /**
     * A billing plan and its full installment schedule are created together, so
     * the event always carries the complete schedule a consumer needs to open
     * receivables.
     */
    static EventEnvelope billingPlanCreated(Policy policy, PolicyBillingPlan plan,
                                            List<PolicyInstallment> installments, Instant now) {
        Objects.requireNonNull(plan, "plan");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("planId", plan.id());
        data.put("planNumber", plan.planNumber());
        data.put("totalPremium", money(plan.totalPremium()));
        data.put("currency", plan.currency());
        data.put("installmentCount", plan.installmentCount());
        data.put("frequency", plan.frequency());
        data.put("firstDueDate", date(plan.firstDueDate()));
        data.put("status", plan.status());
        data.put("installments", installments == null
                ? List.of()
                : installments.stream().map(PolicyEvents::installmentData).toList());
        return envelope(policy, BILLING_PLAN_CREATED, data, now);
    }

    /**
     * A positive premium adjustment appends one DUE installment and moves the
     * plan total premium up by the adjustment amount, so the plan's net premium
     * (total less applied credits) catches up to the policy premium. The payload
     * is the adjustment contract: the policy and plan identity, the appended
     * amount, the resulting gross plan total, and the appended installment
     * number, due date, and reason.
     */
    static EventEnvelope billingPlanAdjusted(Policy policy, PolicyBillingPlan plan,
                                             PolicyInstallment installment,
                                             BigDecimal adjustmentAmount, String reason,
                                             Instant now) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(installment, "installment");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("planId", plan.id());
        data.put("adjustmentAmount", money(adjustmentAmount));
        data.put("newTotalPremium", money(plan.totalPremium()));
        data.put("installmentId", installment.id());
        data.put("installmentNumber", installment.installmentNumber());
        data.put("dueDate", date(installment.dueDate()));
        data.put("reason", reason);
        return envelope(policy, BILLING_PLAN_ADJUSTED, data, now);
    }

    /**
     * A DRAFT credit note is a policy aggregate change: the payload carries the
     * policy and note identity, the money value, the creation instant, and the
     * initial DRAFT status. When the note refunds a cancellation, the payload
     * also carries {@code cancellationId} and {@code cancellationNumber} so a
     * receivables consumer can reconcile the return premium without a second
     * read. An unlinked billing adjustment omits both members.
     */
    static EventEnvelope creditNoteCreated(Policy policy, PolicyCreditNote creditNote,
                                           Instant now) {
        Objects.requireNonNull(creditNote, "creditNote");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("creditNoteId", creditNote.id());
        data.put("noteNumber", creditNote.noteNumber());
        data.put("amount", money(creditNote.amount()));
        data.put("currency", creditNote.currency());
        data.put("status", creditNote.status());
        data.put("createdAt", instant(creditNote.createdAt()));
        if (creditNote.cancellationId() != null) {
            data.put("cancellationId", creditNote.cancellationId());
            data.put("cancellationNumber", creditNote.cancellationNumber() == null
                    ? NullNode.getInstance() : creditNote.cancellationNumber());
        }
        return envelope(policy, CREDIT_NOTE_CREATED, data, now);
    }

    /**
     * A credit note transition is a policy aggregate change: the payload
     * carries the policy and note identifiers, the money value, and the new
     * status. The policy version advances with the transition, so every event
     * gets a distinct {@code aggregateVersion}.
     *
     * <p>An APPLIED event also carries {@code installmentId} and the
     * installment's cumulative {@code creditedAmount} after the application, so
     * a receivables consumer can update the open installment without a second
     * read. ISSUED, CANCELLED, and REVERSED events never carry
     * {@code creditedAmount}; a reversed note is emitted by
     * {@link #creditNoteReversed(Policy, PolicyCreditNote, UUID, Instant)} so
     * its payload matches the reversal contract.</p>
     */
    static EventEnvelope creditNoteStatusChanged(Policy policy, PolicyCreditNote creditNote,
                                                 PolicyInstallment installment, Instant now) {
        Objects.requireNonNull(creditNote, "creditNote");
        if (CreditNoteRules.REVERSED.equals(creditNote.status())) {
            return creditNoteReversed(policy, creditNote,
                    installment == null ? null : installment.id(), now);
        }
        String eventType = switch (creditNote.status()) {
            case CreditNoteRules.ISSUED -> CREDIT_NOTE_ISSUED;
            case CreditNoteRules.APPLIED -> CREDIT_NOTE_APPLIED;
            case CreditNoteRules.CANCELLED -> CREDIT_NOTE_CANCELLED;
            default -> throw new IllegalArgumentException(
                    "Unsupported credit note status: " + creditNote.status());
        };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("creditNoteId", creditNote.id());
        data.put("noteNumber", creditNote.noteNumber());
        data.put("amount", money(creditNote.amount()));
        data.put("currency", creditNote.currency());
        data.put("status", creditNote.status());
        if (CreditNoteRules.APPLIED.equals(creditNote.status())) {
            Objects.requireNonNull(installment,
                    "An APPLIED credit note event requires the credited installment");
            data.put("installmentId", installment.id());
            data.put("creditedAmount", money(installment.creditedAmount()));
        }
        return envelope(policy, eventType, data, now);
    }

    /** Compatibility form for transitions that do not name an installment. */
    static EventEnvelope creditNoteStatusChanged(Policy policy, PolicyCreditNote creditNote,
                                                 Instant now) {
        return creditNoteStatusChanged(policy, creditNote, null, now);
    }

    /**
     * A reversal releases the note's credit and explains why. The payload is the
     * reversal contract: the note identifiers, the installment that received the
     * credit (explicitly null for a legacy APPLIED note that predates
     * installment application), the note amount, the reversal reason, and the
     * new REVERSED status.
     */
    static EventEnvelope creditNoteReversed(Policy policy, PolicyCreditNote creditNote,
                                            UUID installmentId, Instant now) {
        Objects.requireNonNull(creditNote, "creditNote");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("creditNoteId", creditNote.id());
        data.put("noteNumber", creditNote.noteNumber());
        data.put("installmentId",
                installmentId == null ? NullNode.getInstance() : installmentId);
        data.put("amount", money(creditNote.amount()));
        data.put("reason", creditNote.reversalReason());
        data.put("status", creditNote.status());
        return envelope(policy, CREDIT_NOTE_REVERSED, data, now);
    }

    /**
     * A program is a catalog aggregate in the policy vertical, so its creation
     * is reported as {@code policy.program.created} against the Program
     * aggregate with aggregate version 1.
     */
    static EventEnvelope programCreated(PolicyProgram program, Instant now) {
        Objects.requireNonNull(program, "program");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("programId", program.id());
        data.put("programNumber", program.programNumber());
        data.put("name", program.name());
        data.put("programType", program.programType());
        data.put("effectiveFrom", date(program.effectiveFrom()));
        data.put("effectiveTo", date(program.effectiveTo()));
        data.put("status", program.status());
        // The canonical event JSON omits null map values; a JSON null node keeps
        // the nullable contract member present for consumers.
        data.put("leadPolicyId",
                program.leadPolicyId() == null ? NullNode.getInstance() : program.leadPolicyId());
        data.put("createdAt", instant(program.createdAt()));
        return programEnvelope(program, PROGRAM_CREATED, data, now);
    }

    /**
     * Program assignment is a policy command: the policy aggregate version
     * advances and the event always carries {@code programId}, which is null
     * when the link is cleared.
     */
    static EventEnvelope programAssigned(Policy before, Policy after, Instant now) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<String, Object> data = base(after);
        data.put("programId",
                after.programId() == null ? NullNode.getInstance() : after.programId());
        data.put("previousProgramId",
                before.programId() == null ? NullNode.getInstance() : before.programId());
        data.put("changedFields", List.of("programId"));
        return envelope(after, PROGRAM_ASSIGNED, data, now);
    }

    /**
     * Legal entity assignment is a policy command: the policy aggregate version
     * advances and the event always carries {@code legalEntityId}, which is null
     * when the link is cleared.
     */
    static EventEnvelope legalEntityAssigned(Policy before, Policy after, Instant now) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<String, Object> data = base(after);
        data.put("legalEntityId",
                after.legalEntityId() == null
                        ? NullNode.getInstance() : after.legalEntityId());
        data.put("previousLegalEntityId",
                before.legalEntityId() == null
                        ? NullNode.getInstance() : before.legalEntityId());
        data.put("changedFields", List.of("legalEntityId"));
        return envelope(after, LEGAL_ENTITY_ASSIGNED, data, now);
    }

    /**
     * A legal entity is a catalog aggregate in the policy vertical, so its
     * creation is reported as {@code policy.legal-entity.created} against the
     * LegalEntity aggregate with aggregate version 1.
     */
    static EventEnvelope legalEntityCreated(LegalEntity legalEntity, Instant now) {
        Objects.requireNonNull(legalEntity, "legalEntity");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("legalEntityId", legalEntity.id());
        data.put("code", legalEntity.code());
        data.put("name", legalEntity.name());
        data.put("entityType", legalEntity.entityType());
        data.put("jurisdiction", legalEntity.jurisdiction());
        // The canonical event JSON omits null map values; a JSON null node keeps
        // the nullable contract members present for consumers.
        data.put("regulator",
                legalEntity.regulator() == null
                        ? NullNode.getInstance() : legalEntity.regulator());
        data.put("licenseNumber",
                legalEntity.licenseNumber() == null
                        ? NullNode.getInstance() : legalEntity.licenseNumber());
        data.put("status", legalEntity.status());
        data.put("createdAt", instant(legalEntity.createdAt()));
        return legalEntityEnvelope(legalEntity, LEGAL_ENTITY_CREATED, data, now);
    }

    /**
     * An underwriting submission is a tenant-owned aggregate in the policy
     * vertical. Its creation event carries the intake facts a downstream
     * clearance, referral, or rating consumer needs: the applicant, product,
     * requested term and limit, tort codes, notes, and initial status.
     * {@code UnderwritingSubmission} is not a policy, so the aggregate type and
     * subject are submission-specific and the aggregate version starts at 1.
     */
    static EventEnvelope submissionCreated(UnderwritingSubmission submission, Instant now) {
        Objects.requireNonNull(submission, "submission");
        Map<String, Object> data = submissionData(submission);
        data.put("createdAt", instant(submission.createdAt()));
        data.put("updatedAt", instant(submission.updatedAt()));
        return submissionEnvelope(submission, SUBMISSION_CREATED, data,
                submission.version() + 1L, now);
    }

    /**
     * A submission transition advances the aggregate version. The payload names
     * the previous and new status and carries the optional operator reason; the
     * reason is event-only because the submission row records just the
     * resulting status and version.
     */
    static EventEnvelope submissionStatusChanged(UnderwritingSubmission before,
                                                 UnderwritingSubmission after, String reason,
                                                 Instant now) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", after.id());
        data.put("submissionNumber", after.submissionNumber());
        data.put("applicantPartyId", after.applicantPartyId());
        data.put("productCode", after.productCode());
        data.put("previousStatus", before.status());
        data.put("status", after.status());
        data.put("version", after.version());
        data.put("updatedAt", instant(after.updatedAt()));
        data.put("reason", reason == null ? NullNode.getInstance() : reason);
        return submissionEnvelope(after, SUBMISSION_STATUS_CHANGED, data,
                after.version() + 1L, now);
    }

    /**
     * A structured referral moves the submission into REFERRED and starts a
     * PENDING decision. The payload carries the referral reason, authority,
     * assignee, optional notes, and the resulting decision/status/version for
     * downstream review queues and audit consumers.
     */
    static EventEnvelope submissionReferred(UnderwritingSubmission before,
                                            UnderwritingSubmission after, Instant now) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", after.id());
        data.put("submissionNumber", after.submissionNumber());
        data.put("applicantPartyId", after.applicantPartyId());
        data.put("productCode", after.productCode());
        data.put("previousStatus", before.status());
        data.put("status", after.status());
        data.put("referralReason", after.referralReason());
        data.put("referralAuthority", after.referralAuthority());
        data.put("referredTo", after.referredTo());
        data.put("referralNotes",
                after.referralNotes() == null
                        ? NullNode.getInstance() : after.referralNotes());
        data.put("referralDecision", after.referralDecision());
        data.put("version", after.version());
        data.put("updatedAt", instant(after.updatedAt()));
        return submissionEnvelope(after, SUBMISSION_REFERRED, data,
                after.version() + 1L, now);
    }

    /**
     * A referral decision closes the pending review. An approval keeps the
     * submission REFERRED; a declination also moves the lifecycle to DECLINED.
     * The payload names both the decision and the resulting submission status
     * so a consumer does not have to infer one from the other.
     */
    static EventEnvelope submissionReferralDecided(UnderwritingSubmission before,
                                                   UnderwritingSubmission after, Instant now) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", after.id());
        data.put("submissionNumber", after.submissionNumber());
        data.put("applicantPartyId", after.applicantPartyId());
        data.put("productCode", after.productCode());
        data.put("previousStatus", before.status());
        data.put("status", after.status());
        data.put("decision", after.referralDecision());
        data.put("decidedBy", after.referralDecidedBy());
        data.put("decidedAt", instant(after.referralDecidedAt()));
        data.put("notes",
                after.referralNotes() == null
                        ? NullNode.getInstance() : after.referralNotes());
        data.put("referralReason", after.referralReason());
        data.put("referralAuthority", after.referralAuthority());
        data.put("referredTo", after.referredTo());
        data.put("version", after.version());
        data.put("updatedAt", instant(after.updatedAt()));
        return submissionEnvelope(after, SUBMISSION_REFERRAL_DECIDED, data,
                after.version() + 1L, now);
    }

    /**
     * Conversion closes the submission lifecycle and creates its policy. The
     * payload is deliberately the four identity members a downstream
     * policy-registration or document consumer needs: the submission side and
     * the policy side. The submission aggregate version advances with the
     * conversion, so the event is distinct from the status transitions that
     * preceded it.
     */
    static EventEnvelope submissionConverted(UnderwritingSubmission submission, Policy policy,
                                             Instant now) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(policy, "policy");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", submission.id());
        data.put("submissionNumber", submission.submissionNumber());
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        return submissionEnvelope(submission, SUBMISSION_CONVERTED, data,
                submission.version() + 1L, now);
    }

    private static Map<String, Object> submissionData(UnderwritingSubmission submission) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", submission.id());
        data.put("submissionNumber", submission.submissionNumber());
        data.put("applicantPartyId", submission.applicantPartyId());
        data.put("productCode", submission.productCode());
        data.put("requestedEffectiveDate", date(submission.requestedEffectiveDate()));
        data.put("requestedLimitAmount", money(submission.requestedLimitAmount()));
        data.put("requestedLimitCurrency", submission.requestedLimitCurrency());
        data.put("tortCodes", submission.tortCodes());
        data.put("status", submission.status());
        data.put("notes",
                submission.notes() == null ? NullNode.getInstance() : submission.notes());
        data.put("version", submission.version());
        return data;
    }

    private static EventEnvelope submissionEnvelope(UnderwritingSubmission submission,
                                                    String eventType,
                                                    Map<String, Object> data,
                                                    long aggregateVersion, Instant now) {
        UUID eventId = EventIds.deterministic(submission.tenantId(),
                SUBMISSION_AGGREGATE_TYPE, submission.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(submission.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(SUBMISSION_AGGREGATE_TYPE)
                .aggregateId(submission.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-policy-expert"))
                .subject(EventSubject.of(VERTICAL, SUBMISSION_SUBJECT_TYPE, submission.id(),
                        submission.submissionNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    private static EventEnvelope legalEntityEnvelope(LegalEntity legalEntity, String eventType,
                                                      Map<String, Object> data, Instant now) {
        UUID eventId = EventIds.deterministic(legalEntity.tenantId(),
                LEGAL_ENTITY_AGGREGATE_TYPE, legalEntity.id(), 1L, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(legalEntity.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(LEGAL_ENTITY_AGGREGATE_TYPE)
                .aggregateId(legalEntity.id())
                .aggregateVersion(1)
                .sequence(0)
                .actor(EventActor.system("eia-policy-expert"))
                .subject(EventSubject.of(VERTICAL, LEGAL_ENTITY_SUBJECT_TYPE,
                        legalEntity.id(), legalEntity.code()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    private static EventEnvelope programEnvelope(PolicyProgram program, String eventType,
                                                 Map<String, Object> data, Instant now) {
        UUID eventId = EventIds.deterministic(program.tenantId(), PROGRAM_AGGREGATE_TYPE,
                program.id(), 1L, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(program.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(PROGRAM_AGGREGATE_TYPE)
                .aggregateId(program.id())
                .aggregateVersion(1)
                .sequence(0)
                .actor(EventActor.system("eia-policy-expert"))
                .subject(EventSubject.of(VERTICAL, PROGRAM_SUBJECT_TYPE, program.id(),
                        program.programNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    /**
     * The profile payload is the allocation contract consumed by Direct Claims:
     * the tort, trigger theory, allocation method, and coverage block, plus the
     * profile status. The profile id is deliberately not part of the payload;
     * the policy is the aggregate and the event is keyed by its version.
     */
    static EventEnvelope massTortProfileUpdated(Policy policy, PolicyMassTortProfile profile,
                                                Instant now) {
        Objects.requireNonNull(profile, "profile");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("status", profile.status());
        data.put("tortType", profile.tortType());
        data.put("triggerType", profile.triggerType());
        data.put("allocationMethod", profile.allocationMethod());
        data.put("coverageBlockStart", date(profile.coverageBlockStart()));
        data.put("coverageBlockEnd", date(profile.coverageBlockEnd()));
        return envelope(policy, MASS_TORT_PROFILE_UPDATED, data, now);
    }

    /**
     * A dunning action is a policy-level collection fact: the payload carries
     * the policy, the dunned installment, the escalation level, the delivery
     * channel, the send instant, and the action status. The policy aggregate
     * version advances with the action, so every dunning event is distinct.
     */
    static EventEnvelope dunningSent(Policy policy, BillingDunningAction action, Instant now) {
        Objects.requireNonNull(action, "action");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("installmentId", action.installmentId());
        data.put("level", action.level());
        data.put("channel", action.channel());
        data.put("sentAt", instant(action.sentAt()));
        data.put("status", action.status());
        return envelope(policy, DUNNING_SENT, data, now);
    }

    static EventEnvelope installmentPaid(Policy policy, PolicyBillingPlan plan,
                                         PolicyInstallment installment, Instant now) {
        return installmentSettled(policy, plan, installment, INSTALLMENT_PAID, now);
    }

    static EventEnvelope installmentWaived(Policy policy, PolicyBillingPlan plan,
                                           PolicyInstallment installment, Instant now) {
        return installmentSettled(policy, plan, installment, INSTALLMENT_WAIVED, now);
    }

    private static EventEnvelope installmentSettled(Policy policy, PolicyBillingPlan plan,
                                                    PolicyInstallment installment, String eventType,
                                                    Instant now) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(installment, "installment");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("planId", plan.id());
        data.put("planNumber", plan.planNumber());
        data.put("installmentId", installment.id());
        data.put("number", installment.installmentNumber());
        data.put("dueDate", date(installment.dueDate()));
        data.put("amount", money(installment.amount()));
        data.put("currency", plan.currency());
        data.put("status", installment.status());
        data.put("paidAt", instant(installment.paidAt()));
        data.put("paymentId", installment.paymentId());
        return envelope(policy, eventType, data, now);
    }

    private static Map<String, Object> installmentData(PolicyInstallment installment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("installmentId", installment.id());
        item.put("number", installment.installmentNumber());
        item.put("dueDate", date(installment.dueDate()));
        item.put("amount", money(installment.amount()));
        item.put("status", installment.status());
        return item;
    }

    static EventEnvelope created(Policy policy, Instant now) {
        Map<String, Object> data = base(policy);
        data.put("productCode", policy.productCode());
        data.put("insuredPartyId", policy.insuredPartyId());
        data.put("createdAt", instant(policy.createdAt()));
        return envelope(policy, CREATED, data, now);
    }

    static EventEnvelope updated(Policy before, Policy after, String reason, Instant now) {
        Map<String, Object> data = base(after);
        data.put("changedFields", List.of("status"));
        data.put("from", before.status());
        data.put("to", after.status());
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason.trim());
        }
        return envelope(after, UPDATED, data, now);
    }

    static EventEnvelope versionIssued(Policy policy, PolicyVersion version, Instant now) {
        Objects.requireNonNull(version, "version");
        Map<String, Object> data = base(policy);
        data.put("policyVersionId", version.id());
        data.put("versionNumber", version.versionNumber());
        data.put("policyVersionNumber", version.policyVersionNumber());
        data.put("changeType", version.changeType());
        data.put("policyVersionStatus", version.status());
        data.put("versionEffectiveFrom", date(version.effectiveFrom()));
        data.put("versionEffectiveTo", date(version.effectiveTo()));
        data.put("issuedAt", instant(version.issuedAt()));
        data.put("issuedBy", version.issuedBy());
        data.put("approvedBy", version.approvedBy());
        data.put("description", version.description());
        data.put("immutableHash", version.immutableHash());
        data.put("supersedesVersionId", version.supersedesVersionId());
        data.put("premiumDeltaAmount", money(version.premiumDeltaAmount()));
        data.put("premiumDeltaCurrency", version.premiumDeltaCurrency());
        return envelope(policy, VERSION_ISSUED, data, now);
    }

    static EventEnvelope endorsed(Policy policy, PolicyEndorsement endorsement,
                                  PolicyVersion version, Instant now) {
        Map<String, Object> data = base(policy);
        data.put("endorsementId", endorsement.id());
        data.put("endorsementNumber", endorsement.endorsementNumber());
        data.put("sequence", endorsement.sequence());
        data.put("endorsementType", endorsement.endorsementType());
        data.put("endorsementEffectiveDate", date(endorsement.effectiveDate()));
        data.put("description", endorsement.description());
        data.put("premiumDeltaAmount", money(endorsement.premiumDeltaAmount()));
        data.put("premiumDeltaCurrency", endorsement.premiumDeltaCurrency());
        data.put("coverageImpact", endorsement.coverageImpact());
        data.put("coverageChanges", coverageChanges(endorsement.coverageChanges()));
        data.put("backdateReason", endorsement.backdateReason());
        data.put("approvalRef", endorsement.approvalRef());
        data.put("approvedBy", endorsement.approvedBy());
        putVersion(data, version);
        return envelope(policy, ENDORSED, data, now);
    }

    /**
     * A premium audit replaces the policy premium. The payload is the audit
     * contract: the policy identity, the premium before and after the audit,
     * the signed movement, the policy currency, the audit business date, and
     * the mandatory reason.
     */
    static EventEnvelope premiumAudited(Policy policy, BigDecimal previousPremium,
                                        BigDecimal auditedPremium, BigDecimal delta,
                                        LocalDate effectiveDate, String reason, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("previousPremium", money(previousPremium));
        data.put("auditedPremium", money(auditedPremium));
        data.put("delta", money(delta));
        data.put("currency", policy.premiumCurrency());
        data.put("effectiveDate", date(effectiveDate));
        data.put("reason", reason);
        return envelope(policy, PREMIUM_AUDITED, data, now);
    }

    /**
     * Applying a rating worksheet replaces the policy premium. The payload is
     * the rating contract: the policy identity, the written premium, the
     * currency, the method, the canonical calculation hash, and the worksheet
     * that evidences the calculation. A calculation-only request writes no
     * event.
     */
    static EventEnvelope rated(Policy policy, PolicyRatingWorksheet worksheet, Instant now) {
        Objects.requireNonNull(worksheet, "worksheet");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("premiumAmount", money(worksheet.computedPremium()));
        data.put("currency", worksheet.currency());
        data.put("ratingMethod", worksheet.ratingMethod());
        data.put("calcHash", worksheet.calcHash());
        data.put("worksheetId", worksheet.id());
        return envelope(policy, RATED, data, now);
    }

    static EventEnvelope cancelled(Policy policy, PolicyCancellation cancellation,
                                   PolicyVersion version, Instant now) {
        Map<String, Object> data = base(policy);
        data.put("cancellationId", cancellation.id());
        data.put("cancellationType", cancellation.cancellationType());
        data.put("reason", cancellation.reason());
        data.put("cancellationEffectiveDate", date(cancellation.effectiveDate()));
        data.put("noticeDays", cancellation.noticeDays());
        data.put("termDays", cancellation.termDays());
        data.put("remainingDays", cancellation.remainingDays());
        data.put("proRataPct", percent(cancellation.proRataPct()));
        data.put("earnedPremiumAmount", money(cancellation.earnedPremiumAmount()));
        data.put("returnPremiumAmount", money(cancellation.returnPremiumAmount()));
        data.put("approvalRef", cancellation.approvalRef());
        data.put("approvedBy", cancellation.approvedBy());
        putVersion(data, version);
        return envelope(policy, CANCELLED, data, now);
    }

    static EventEnvelope reinstated(Policy policy, PolicyReinstatement reinstatement,
                                    PolicyVersion version, Instant now) {
        Map<String, Object> data = base(policy);
        data.put("reinstatementId", reinstatement.id());
        data.put("cancellationId", reinstatement.cancellationId());
        data.put("reinstatementEffectiveDate", date(reinstatement.effectiveDate()));
        data.put("reason", reinstatement.reason());
        data.put("approvalRef", reinstatement.approvalRef());
        data.put("approvedBy", reinstatement.approvedBy());
        data.put("premiumEvidenceRef", reinstatement.premiumEvidenceRef());
        data.put("premiumEvidenceAmount", money(reinstatement.premiumEvidenceAmount()));
        data.put("premiumEvidenceCurrency", reinstatement.premiumEvidenceCurrency());
        putVersion(data, version);
        return envelope(policy, REINSTATED, data, now);
    }

    /**
     * Renewal creates a new DRAFT term without mutating the source, so the
     * payload always carries both sides of the lineage: the source policy and
     * the new policy, plus the new term dates, premium, and limit.
     */
    static EventEnvelope renewed(Policy source, Policy renewal, Instant now) {
        Map<String, Object> data = base(renewal);
        data.put("renewedFromPolicyId", renewal.renewedFromPolicyId());
        data.put("sourcePolicyId", source.id());
        data.put("sourcePolicyNumber", source.policyNumber());
        data.put("newPolicyId", renewal.id());
        data.put("newPolicyNumber", renewal.policyNumber());
        return envelope(renewal, RENEWED, data, now);
    }

    private static Map<String, Object> base(Policy policy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyId", policy.id());
        data.put("policyNumber", policy.policyNumber());
        data.put("status", policy.status());
        data.put("version", policy.version());
        data.put("effectiveDate", date(policy.effectiveDate()));
        data.put("expirationDate", date(policy.expirationDate()));
        data.put("premiumAmount", money(policy.premiumAmount()));
        data.put("premiumCurrency", policy.premiumCurrency());
        data.put("limitAmount", money(policy.limitAmount()));
        data.put("limitCurrency", policy.limitCurrency());
        return data;
    }

    private static void putVersion(Map<String, Object> data, PolicyVersion version) {
        if (version == null) {
            return;
        }
        data.put("policyVersionId", version.id());
        data.put("versionNumber", version.versionNumber());
        data.put("policyVersionNumber", version.policyVersionNumber());
        data.put("versionEffectiveFrom", date(version.effectiveFrom()));
        data.put("immutableHash", version.immutableHash());
    }

    private static List<Map<String, Object>> coverageChanges(
            List<EndorsementCoverageChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        return changes.stream().map(change -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("coverageCode", change.coverageCode());
            item.put("changeType", change.changeType());
            if (change.description() != null) {
                item.put("description", change.description());
            }
            return item;
        }).toList();
    }

    private static EventEnvelope envelope(Policy policy, String eventType,
                                          Map<String, Object> data, Instant now) {
        long aggregateVersion = policy.version() + 1L;
        UUID eventId = EventIds.deterministic(
                policy.tenantId(), AGGREGATE_TYPE, policy.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(policy.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(policy.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-policy-expert"))
                .subject(EventSubject.of(VERTICAL, SUBJECT_TYPE, policy.id(),
                        policy.policyNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : Money.of(amount).toPlainString();
    }

    private static String percent(BigDecimal fraction) {
        return fraction == null ? null : Money.percent(fraction).toPlainString();
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
