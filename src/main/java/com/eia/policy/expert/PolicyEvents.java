package com.eia.policy.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

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
    static final String CANCELLED = "policy.policy.cancelled";
    static final String REINSTATED = "policy.policy.reinstated";
    static final String BILLING_PLAN_CREATED = "policy.billing-plan.created";
    static final String INSTALLMENT_PAID = "policy.installment.paid";
    static final String INSTALLMENT_WAIVED = "policy.installment.waived";

    private static final Book EVENT_BOOK = Book.DIRECT;
    private static final String VERTICAL = "policy";
    private static final String AGGREGATE_TYPE = "Policy";
    private static final String SUBJECT_TYPE = "policy";
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
