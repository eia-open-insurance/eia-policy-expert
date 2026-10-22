package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Submission-to-policy conversion (SPEC 11.2.14).
 *
 * <p>A QUOTED submission is converted by writing a new DRAFT policy from its
 * applicant, product, requested effective date, and limit, plus the premium
 * supplied by the command. A BOUND submission without a policy is also
 * accepted so the intake status graph and the conversion command can be used
 * independently; the link is still recorded and the submission version
 * advances. Every other status is a {@code 409 SUBMISSION_NOT_CONVERTIBLE}.</p>
 *
 * <p>The policy is created through {@link PolicyService} so it gets the normal
 * server-allocated {@code POL-YYYY-NNNNNNN} number, initial DRAFT policy
 * version, and {@code policy.policy.created} event. It uses the submission's
 * already-accepted product and therefore bypasses the ACTIVE-catalog guard: a
 * product retired after intake must not block the conversion. Conversion then
 * adds one base coverage and its limit schedule when the submission requested a
 * positive limit. Coverage and limit invariants come from
 * {@link CoverageRules}, the same rules the coverage command uses; the limit is
 * both the each-occurrence and the aggregate amount because the submission
 * carries a single requested limit.</p>
 *
 * <p>The submission row is locked for the command, so two concurrent
 * conversions cannot both create a policy. When the submission is already
 * BOUND and a policy is linked, the command is an idempotent replay: it returns
 * the existing policy with {@code replayed=true}, writes nothing, and emits no
 * event. The policy link, the submission status/version, and the
 * {@code policy.submission.converted} event commit together.</p>
 */
@Service
public class SubmissionConversionService {
    private static final BigDecimal ZERO_MONEY =
            BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO_PERCENT =
            BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

    private final UnderwritingSubmissionRepository submissionRepository;
    private final PolicyRepository policyRepository;
    private final PolicyService policyService;
    private final CoverageRepository coverageRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public SubmissionConversionService(UnderwritingSubmissionRepository submissionRepository,
                                       PolicyRepository policyRepository,
                                       PolicyService policyService,
                                       CoverageRepository coverageRepository,
                                       OutboxRepository outbox) {
        this(submissionRepository, policyRepository, policyService, coverageRepository, outbox,
                Clock.systemUTC());
    }

    SubmissionConversionService(UnderwritingSubmissionRepository submissionRepository,
                                PolicyRepository policyRepository, PolicyService policyService,
                                CoverageRepository coverageRepository, OutboxRepository outbox,
                                Clock clock) {
        this.submissionRepository = submissionRepository;
        this.policyRepository = policyRepository;
        this.policyService = policyService;
        this.coverageRepository = coverageRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public SubmissionConversionResult convert(UUID tenantId, UUID submissionId,
                                              ConvertSubmissionRequest request) {
        UnderwritingSubmission submission = submissionRepository
                .findByIdForUpdate(tenantId, submissionId)
                .orElseThrow(() -> PolicyErrors.notFound("SUBMISSION_NOT_FOUND", "submissionId",
                        "Underwriting submission not found: " + submissionId));

        Optional<Policy> existing = policyRepository.findBySubmissionId(tenantId, submissionId);
        if (existing.isPresent()) {
            if (!UnderwritingSubmissionRules.isConvertible(submission.status())) {
                throw notConvertible(submission);
            }
            Policy policy = existing.get();
            return new SubmissionConversionResult(policy,
                    UnderwritingSubmissionView.of(submission, policy.id()), true);
        }

        if (!UnderwritingSubmissionRules.isConvertible(submission.status())) {
            throw notConvertible(submission);
        }

        UnderwritingSubmissionRules.ConversionSpec spec =
                UnderwritingSubmissionRules.validateConversion(submission, request);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        Policy created = policyService.createConvertedPolicy(new CreatePolicyRequest(
                tenantId,
                null,
                submission.productCode(),
                submission.applicantPartyId(),
                "DRAFT",
                submission.requestedEffectiveDate(),
                spec.expirationDate(),
                spec.premiumAmount(),
                spec.premiumCurrency(),
                submission.requestedLimitAmount(),
                submission.requestedLimitCurrency()));

        initializeCoverage(created, submission, now);

        int policyRows = policyRepository.assignSubmission(
                tenantId, created.id(), submissionId, created.version(), now);
        if (policyRows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "If-Match",
                    "The policy changed concurrently; reload and retry.");
        }
        Policy policy = policyRepository.findById(tenantId, created.id())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR", "Converted policy could not be reloaded."));

        int submissionRows;
        if (UnderwritingSubmissionRules.QUOTED.equals(submission.status())) {
            submissionRows = submissionRepository.updateStatus(tenantId, submissionId,
                    UnderwritingSubmissionRules.BOUND, UnderwritingSubmissionRules.QUOTED,
                    submission.version(), now);
        } else {
            // BOUND without a policy: record the link and advance the aggregate
            // version without inventing a second status transition.
            submissionRows = submissionRepository.touch(
                    tenantId, submissionId, submission.version(), now);
        }
        if (submissionRows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "expectedVersion",
                    "The submission changed concurrently; reload and retry.",
                    Map.of("currentVersion", submission.version()));
        }

        UnderwritingSubmission updated = submissionRepository.findById(tenantId, submissionId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR", "Converted submission could not be reloaded."));
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.submissionConverted(updated, policy, now)));
        return new SubmissionConversionResult(policy,
                UnderwritingSubmissionView.of(updated, policy.id()), false);
    }

    /**
     * Adds one base coverage and its limit schedule to a newly converted DRAFT
     * policy. A submission whose requested limit is zero gets no coverage,
     * because the coverage rule requires a positive each-occurrence limit.
     * The coverage code is derived from the product code and normalized to the
     * coverage-code alphabet; the name keeps the product code for operators.
     */
    private void initializeCoverage(Policy policy, UnderwritingSubmission submission, Instant now) {
        BigDecimal requestedLimit = submission.requestedLimitAmount();
        if (requestedLimit == null || requestedLimit.signum() <= 0) {
            return;
        }
        String coverageCode = defaultCoverageCode(submission.productCode());
        String coverageName = defaultCoverageName(submission.productCode());
        CreateCoverageRequest coverageRequest = new CreateCoverageRequest(
                coverageCode,
                coverageName,
                "A",
                "OCCURRENCE",
                policy.effectiveDate(),
                policy.expirationDate(),
                null,
                new CreateCoverageRequest.LimitSpec(
                        requestedLimit,
                        requestedLimit,
                        "PER_POLICY",
                        null,
                        null,
                        null,
                        submission.requestedLimitCurrency(),
                        "PAID_PLUS_RESERVED"));
        CoverageRules.CoverageSpec spec = CoverageRules.validate(coverageRequest,
                policy.limitCurrency(), policy.effectiveDate(), policy.expirationDate());

        UUID coverageId = UUID.randomUUID();
        UUID limitId = UUID.randomUUID();
        int sequence = coverageRepository.nextCoverageSequence(policy.tenantId(), policy.id());
        PolicyCoverage coverage = new PolicyCoverage(
                coverageId, policy.tenantId(), policy.id(), spec.coverageCode(),
                spec.coverageName(), spec.coveragePart(), spec.occurrenceBasis(),
                spec.coinsurancePct(), spec.effectiveFrom(), spec.effectiveTo(), "ACTIVE",
                sequence, now);
        CoverageRules.LimitSpec limitSpec = spec.limit();
        PolicyLimit limit = new PolicyLimit(
                limitId, policy.tenantId(), policy.id(), coverageId,
                limitSpec.perOccurrenceAmount(), limitSpec.aggregateAmount(),
                limitSpec.aggregateBasis(), limitSpec.sublimitAmount(),
                limitSpec.deductibleAmount(), limitSpec.coinsurancePct(),
                limitSpec.currency(), limitSpec.erosionBasis(), spec.effectiveFrom(),
                spec.effectiveTo(), sequence, now);
        BigDecimal base = limit.erosionBase();
        PolicyLimitState state = new PolicyLimitState(
                UUID.randomUUID(), policy.tenantId(), policy.id(), limitId, now, base,
                ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, base, ZERO_PERCENT,
                ZERO_MONEY, 0L, now, "CURRENT", 0);

        coverageRepository.insertCoverage(coverage);
        coverageRepository.insertLimit(limit);
        coverageRepository.insertState(state);
    }

    /**
     * Uppercases the product code and maps characters outside the coverage-code
     * alphabet to underscores, preserving the common {@code CGL_PRODUCTS} case.
     * A leading digit gets a letter prefix and the result is capped at 32.
     */
    static String defaultCoverageCode(String productCode) {
        String candidate = productCode == null
                ? ""
                : productCode.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (candidate.isEmpty() || !Character.isLetter(candidate.charAt(0))) {
            candidate = "C_" + candidate;
        }
        if (candidate.length() > 32) {
            candidate = candidate.substring(0, 32);
        }
        if (candidate.length() < 2) {
            candidate = candidate + "_";
        }
        return candidate;
    }

    private static String defaultCoverageName(String productCode) {
        String name = productCode == null || productCode.isBlank()
                ? "Base coverage"
                : productCode.trim() + " base coverage";
        return name.length() > 160 ? name.substring(0, 160) : name;
    }

    private static ApiException notConvertible(UnderwritingSubmission submission) {
        String message = "Submission " + submission.id() + " is " + submission.status()
                + " and cannot be converted to a policy.";
        return PolicyErrors.conflict("SUBMISSION_NOT_CONVERTIBLE", "status", message,
                Map.of("submissionId", submission.id().toString(),
                        "currentStatus", submission.status()));
    }
}
