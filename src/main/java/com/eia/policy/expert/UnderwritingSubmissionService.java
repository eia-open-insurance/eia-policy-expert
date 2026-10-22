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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Underwriting submission slice (SPEC 11.2.14 intake).
 *
 * <p>A submission is created {@code RECEIVED} with a server-generated
 * {@code SUB-YYYY-NNNNNNN} number that is unique per tenant. The only mutation
 * is the one-way status graph in {@link UnderwritingSubmissionRules};
 * {@code BOUND}, {@code DECLINED}, and {@code WITHDRAWN} are terminal. Every
 * accepted create and transition writes {@code policy.submission.created} or
 * {@code policy.submission.status-changed} in the same transaction as the row
 * change, so a rollback leaves neither a submission nor an event behind.</p>
 *
 * <p>The transition reason is optional and is carried on the event as the audit
 * explanation. It is not a column on the submission: the aggregate records the
 * resulting status and version, while the outbox retains the reason.</p>
 */
@Service
public class UnderwritingSubmissionService {
    private static final int NUMBER_ALLOCATION_ATTEMPTS = 3;

    private final UnderwritingSubmissionRepository repository;
    private final UnderwritingSubmissionNumberAllocator numberAllocator;
    private final PolicyRepository policyRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public UnderwritingSubmissionService(UnderwritingSubmissionRepository repository,
                                         UnderwritingSubmissionNumberAllocator numberAllocator,
                                         PolicyRepository policyRepository,
                                         OutboxRepository outbox) {
        this(repository, numberAllocator, policyRepository, outbox, Clock.systemUTC());
    }

    UnderwritingSubmissionService(UnderwritingSubmissionRepository repository,
                                  UnderwritingSubmissionNumberAllocator numberAllocator,
                                  PolicyRepository policyRepository,
                                  OutboxRepository outbox, Clock clock) {
        this.repository = repository;
        this.numberAllocator = numberAllocator;
        this.policyRepository = policyRepository;
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
     * Creates a RECEIVED submission. Validation runs before the number is
     * allocated, so a rejected body never consumes a number. The number
     * allocation is its own short transaction; a later rollback of the insert
     * leaves a gap, matching database sequence semantics.
     */
    @Transactional
    public UnderwritingSubmissionView create(UUID tenantId,
                                             CreateUnderwritingSubmissionRequest request) {
        UnderwritingSubmissionRules.UnderwritingSubmissionSpec spec =
                UnderwritingSubmissionRules.validateCreate(request);
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
