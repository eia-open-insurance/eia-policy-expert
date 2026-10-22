package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Underwriting submission slice (SPEC 11.2.14 intake).
 *
 * <p>A submission is created {@code RECEIVED} with a server-generated
 * {@code SUB-YYYY-NNNNNNN} number that is unique per tenant. Its product code
 * must be an ACTIVE catalog product for the tenant
 * ({@link ProductCatalogGuard}); the product is validated once, at intake, and
 * a later retirement never invalidates an existing submission. The only
 * mutation is the one-way status graph in {@link UnderwritingSubmissionRules};
 * {@code BOUND}, {@code DECLINED}, and {@code WITHDRAWN} are terminal. Every
 * accepted create and transition writes {@code policy.submission.created} or
 * {@code policy.submission.status-changed} in the same transaction as the row
 * change, so a rollback leaves neither a submission nor an event behind.</p>
 *
 * <p>The transition reason is optional and is carried on the event as the audit
 * explanation. It is not a column on the submission: the aggregate records the
 * resulting status and version, while the outbox retains the reason.</p>
 *
 * <p>A structured referral is a separate command. It is accepted while the
 * submission is {@code RECEIVED} or {@code REFERRED}, stores the
 * reason/authority/assignee and optional notes, moves the status to
 * {@code REFERRED}, and starts the decision at {@code PENDING}. A decision is
 * accepted only while that referral is pending: {@code APPROVED} leaves the
 * status {@code REFERRED}, while {@code DECLINED} also moves the lifecycle to
 * {@code DECLINED}. Each accepted command writes
 * {@code policy.submission.referred} or
 * {@code policy.submission.referral-decided} in the same transaction as the
 * row change.</p>
 */
@Service
public class UnderwritingSubmissionService {
    private static final int NUMBER_ALLOCATION_ATTEMPTS = 3;

    private final UnderwritingSubmissionRepository repository;
    private final UnderwritingSubmissionNumberAllocator numberAllocator;
    private final PolicyRepository policyRepository;
    private final ProductCatalogGuard productCatalog;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public UnderwritingSubmissionService(UnderwritingSubmissionRepository repository,
                                         UnderwritingSubmissionNumberAllocator numberAllocator,
                                         PolicyRepository policyRepository,
                                         ProductCatalogGuard productCatalog,
                                         OutboxRepository outbox) {
        this(repository, numberAllocator, policyRepository, productCatalog, outbox,
                Clock.systemUTC());
    }

    UnderwritingSubmissionService(UnderwritingSubmissionRepository repository,
                                  UnderwritingSubmissionNumberAllocator numberAllocator,
                                  PolicyRepository policyRepository,
                                  ProductCatalogGuard productCatalog,
                                  OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.numberAllocator = numberAllocator;
        this.policyRepository = policyRepository;
        this.productCatalog = productCatalog;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Lists the tenant's submissions. {@code q} is a case-insensitive match on
     * the submission number, product code, or notes; {@code status} is one
     * known lifecycle value. Both filters are optional and combine with AND.
     */
    public List<UnderwritingSubmissionView> list(UUID tenantId, String q, String status) {
        String query = q == null || q.isBlank() ? null : q.trim();
        String statusFilter = status == null || status.isBlank()
                ? null : UnderwritingSubmissionRules.normalizeStatus(status);
        List<UnderwritingSubmission> submissions = repository.findAll(tenantId, query, statusFilter);
        Map<UUID, UUID> policyIds = policyRepository.findPolicyIdsBySubmissionIds(tenantId,
                submissions.stream().map(UnderwritingSubmission::id).toList());
        return submissions.stream()
                .map(submission -> UnderwritingSubmissionView.of(
                        submission, policyIds.get(submission.id())))
                .toList();
    }

    public UnderwritingSubmissionView get(UUID tenantId, UUID submissionId) {
        UnderwritingSubmission submission = requireSubmission(tenantId, submissionId);
        UUID policyId = policyRepository.findBySubmissionId(tenantId, submissionId)
                .map(Policy::id)
                .orElse(null);
        return UnderwritingSubmissionView.of(submission, policyId);
    }

    /**
     * Creates a RECEIVED submission. The product code must exist for the tenant
     * and be ACTIVE. Validation and the catalog check run before the number is
     * allocated, so a rejected body never consumes a number. The number
     * allocation is its own short transaction; a later rollback of the insert
     * leaves a gap, matching database sequence semantics.
     */
    @Transactional
    public UnderwritingSubmissionView create(UUID tenantId,
                                             CreateUnderwritingSubmissionRequest request) {
        UnderwritingSubmissionRules.UnderwritingSubmissionSpec spec =
                UnderwritingSubmissionRules.validateCreate(request);
        productCatalog.requireActive(tenantId, spec.productCode());
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        String submissionNumber = formatNumber(allocateNumber(tenantId), now);
        UnderwritingSubmission submission = new UnderwritingSubmission(
                UUID.randomUUID(),
                tenantId,
                submissionNumber,
                spec.applicantPartyId(),
                spec.productCode(),
                spec.requestedEffectiveDate(),
                spec.requestedLimitAmount(),
                spec.requestedLimitCurrency(),
                spec.tortCodes(),
                UnderwritingSubmissionRules.RECEIVED,
                spec.notes(),
                0,
                now,
                now);
        try {
            repository.insert(submission);
        } catch (DuplicateKeyException ex) {
            throw PolicyErrors.conflict("DUPLICATE_SUBMISSION_NUMBER", "submissionNumber",
                    "Submission number already exists for this tenant: " + submissionNumber);
        }
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.submissionCreated(submission, now)));
        return UnderwritingSubmissionView.of(submission);
    }

    /**
     * Applies one transition. The update is conditional on the version and
     * status read by this command, so a concurrent winner is a canonical 409
     * rather than a lost update, and a rejected transition writes no event.
     */
    @Transactional
    public UnderwritingSubmissionView updateStatus(
            UUID tenantId, UUID submissionId,
            UpdateUnderwritingSubmissionStatusRequest request) {
        UnderwritingSubmission current = requireSubmission(tenantId, submissionId);
        String target = UnderwritingSubmissionRules.normalizeStatus(
                request == null ? null : request.status());
        if (!UnderwritingSubmissionRules.canTransition(current.status(), target)) {
            throw transitionConflict(current, target);
        }
        String reason = UnderwritingSubmissionRules.normalizeReason(
                request == null ? null : request.reason());

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int rows = repository.updateStatus(tenantId, submissionId, target, current.status(),
                current.version(), now);
        if (rows == 0) {
            UnderwritingSubmission latest = requireSubmission(tenantId, submissionId);
            throw conflict(latest.version(), current.version());
        }
        UnderwritingSubmission updated = requireSubmission(tenantId, submissionId);
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.submissionStatusChanged(current, updated, reason, now)));
        return UnderwritingSubmissionView.of(updated);
    }

    /**
     * Creates or updates the structured referral and moves the submission to
     * {@code REFERRED} with a {@code PENDING} decision. Only
     * {@code RECEIVED}/{@code REFERRED} are referrable; every later status is
     * a state conflict. The update is conditional on the read version and
     * status, so a concurrent winner is a canonical 409 and writes no event.
     */
    @Transactional
    public UnderwritingSubmissionView refer(UUID tenantId, UUID submissionId,
                                            ReferSubmissionRequest request) {
        UnderwritingSubmission current = requireSubmission(tenantId, submissionId);
        UnderwritingSubmissionRules.ReferralSpec referral =
                UnderwritingSubmissionRules.validateReferral(request);
        if (!UnderwritingSubmissionRules.isReferrable(current.status())) {
            throw notReferrable(current);
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int rows = repository.updateReferral(tenantId, submissionId, current.status(),
                current.version(), referral, now);
        if (rows == 0) {
            UnderwritingSubmission latest = requireSubmission(tenantId, submissionId);
            throw conflict(latest.version(), current.version());
        }
        UnderwritingSubmission updated = requireSubmission(tenantId, submissionId);
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.submissionReferred(current, updated, now)));
        return UnderwritingSubmissionView.of(updated);
    }

    /**
     * Decides the pending referral. {@code APPROVED} keeps the submission
     * {@code REFERRED}; {@code DECLINED} also moves it to {@code DECLINED}.
     * The conditional update requires the still-pending decision and the read
     * version, so a duplicate decision or a concurrent transition is a
     * conflict without a row change or an event.
     */
    @Transactional
    public UnderwritingSubmissionView decideReferral(UUID tenantId, UUID submissionId,
                                                     DecideReferralRequest request) {
        UnderwritingSubmission current = requireSubmission(tenantId, submissionId);
        UnderwritingSubmissionRules.ReferralDecisionSpec decision =
                UnderwritingSubmissionRules.validateReferralDecision(request);
        if (!UnderwritingSubmissionRules.isReferralPending(current)) {
            throw referralNotPending(current);
        }

        String targetStatus =
                UnderwritingSubmissionRules.REFERRAL_APPROVED.equals(decision.decision())
                        ? UnderwritingSubmissionRules.REFERRED
                        : UnderwritingSubmissionRules.DECLINED;
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int rows = repository.decideReferral(tenantId, submissionId, targetStatus,
                decision.decision(), decision.decidedBy(), decision.notes(), current.status(),
                current.version(), now);
        if (rows == 0) {
            UnderwritingSubmission latest = requireSubmission(tenantId, submissionId);
            throw conflict(latest.version(), current.version());
        }
        UnderwritingSubmission updated = requireSubmission(tenantId, submissionId);
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.submissionReferralDecided(current, updated, now)));
        return UnderwritingSubmissionView.of(updated);
    }

    private UnderwritingSubmission requireSubmission(UUID tenantId, UUID submissionId) {
        return repository.findById(tenantId, submissionId)
                .orElseThrow(() -> PolicyErrors.notFound("SUBMISSION_NOT_FOUND",
                        "submissionId", "Underwriting submission not found: " + submissionId));
    }

    /**
     * The allocator's first-use insert can lose a primary-key race. Its
     * transaction is independent (REQUIRES_NEW), so the failure rolls back that
     * short transaction only and a retry reads the committed counter row.
     */
    private long allocateNumber(UUID tenantId) {
        for (int attempt = 0; attempt < NUMBER_ALLOCATION_ATTEMPTS; attempt++) {
            try {
                return numberAllocator.next(tenantId);
            } catch (DuplicateKeyException ex) {
                // First submission for this tenant raced; retry against the winner.
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The underwriting submission number sequence could not be allocated.");
    }

    private static String formatNumber(long sequence, Instant now) {
        int year = LocalDate.ofInstant(now, ZoneOffset.UTC).getYear();
        return String.format(Locale.ROOT, "SUB-%04d-%07d", year, sequence);
    }

    private static ApiException transitionConflict(UnderwritingSubmission submission,
                                                    String target) {
        return PolicyErrors.conflict("INVALID_SUBMISSION_TRANSITION", "status",
                "Submission " + submission.id() + " is " + submission.status()
                        + " and cannot transition to " + target + ".",
                Map.of("submissionId", submission.id().toString(),
                        "currentStatus", submission.status(),
                        "targetStatus", target));
    }

    private static ApiException notReferrable(UnderwritingSubmission submission) {
        return PolicyErrors.conflict("SUBMISSION_NOT_REFERRABLE", "status",
                "Submission " + submission.id() + " is " + submission.status()
                        + " and can only be referred while RECEIVED or REFERRED.",
                Map.of("submissionId", submission.id().toString(),
                        "currentStatus", submission.status()));
    }

    private static ApiException referralNotPending(UnderwritingSubmission submission) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("submissionId", submission.id().toString());
        meta.put("currentStatus", submission.status());
        if (submission.referralDecision() != null) {
            meta.put("referralDecision", submission.referralDecision());
        }
        return PolicyErrors.conflict("REFERRAL_NOT_PENDING", "referralDecision",
                "Submission " + submission.id() + " has no pending referral decision (status "
                        + submission.status() + ", referralDecision "
                        + submission.referralDecision() + ").",
                meta);
    }

    private static ApiException conflict(int currentVersion, int expectedVersion) {
        String message = "Expected version " + expectedVersion
                + " but submission is at version " + currentVersion
                + "; reload and retry";
        return new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", message,
                List.of(new ApiException.FieldIssue(
                        "expectedVersion", "STALE_VERSION", message)),
                Map.of("currentVersion", currentVersion, "expectedVersion", expectedVersion));
    }
}
