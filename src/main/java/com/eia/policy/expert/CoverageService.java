package com.eia.policy.expert;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Coverage, limit, and aggregate-erosion slice.
 *
 * <p>Coverage dates must sit inside the policy term (SPEC 11.2.4). The each
 * occurrence, aggregate, sublimit, deductible, coinsurance, and currency are
 * validated by {@link CoverageRules}. The erosion ledger is derived from
 * idempotent facts and rejects any fact that would take a limit negative
 * (SPEC POL-11, POL-12). Adding a coverage after issue is a material change, so
 * it issues an endorsement and a new policy version.
 */
@Service
public class CoverageService {
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO_PERCENT = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

    private final PolicyRepository policyRepository;
    private final MutationGuard guard;
    private final CoverageRepository coverageRepository;
    private final PolicyVersionService versionService;
    private final VersionIssuer versionIssuer;
    private final EndorsementFactory endorsementFactory;
    private final CommandIdempotency idempotency;

    public CoverageService(PolicyRepository policyRepository,
                           MutationGuard guard,
                           CoverageRepository coverageRepository,
                           PolicyVersionService versionService,
                           VersionIssuer versionIssuer,
                           EndorsementFactory endorsementFactory,
                           CommandIdempotency idempotency) {
        this.policyRepository = policyRepository;
        this.guard = guard;
        this.coverageRepository = coverageRepository;
        this.versionService = versionService;
        this.versionIssuer = versionIssuer;
        this.endorsementFactory = endorsementFactory;
        this.idempotency = idempotency;
    }

    public ItemPage<CoverageView> listCoverages(UUID tenantId, UUID policyId, int page, int size) {
        requirePolicy(tenantId, policyId);
        List<PolicyCoverage> coverages = coverageRepository.findCoverages(tenantId, policyId);
        Map<UUID, PolicyLimit> limits = coverageRepository.findLimits(tenantId, policyId).stream()
                .collect(Collectors.toMap(PolicyLimit::coverageId, Function.identity()));
        Map<UUID, PolicyLimitState> states = coverageRepository.findStates(tenantId, policyId).stream()
                .collect(Collectors.toMap(PolicyLimitState::limitId, Function.identity()));
        List<CoverageView> views = coverages.stream()
                .map(coverage -> {
                    PolicyLimit limit = limits.get(coverage.id());
                    return new CoverageView(coverage, limit,
                            limit == null ? null : states.get(limit.id()));
                })
                .toList();
        return SubPages.page(views, page, size,
                "/internal/v1/policies/" + policyId + "/coverages", tenantId);
    }

    public ItemPage<PolicyLimit> listLimits(UUID tenantId, UUID policyId, int page, int size) {
        requirePolicy(tenantId, policyId);
        return SubPages.page(coverageRepository.findLimits(tenantId, policyId), page, size,
                "/internal/v1/policies/" + policyId + "/limits", tenantId);
    }

    public ItemPage<PolicyLimitState> aggregateState(UUID tenantId, UUID policyId, int page, int size) {
        requirePolicy(tenantId, policyId);
        return SubPages.page(coverageRepository.findStates(tenantId, policyId), page, size,
                "/internal/v1/policies/" + policyId + "/aggregate-state", tenantId);
    }

    @Transactional
    public CoverageResult create(UUID tenantId, UUID policyId, CreateCoverageRequest request,
                                 String ifMatch, String rawIdempotencyKey) {
        Policy policy = guard.requirePolicy(tenantId, policyId, ifMatch);
        CoverageRules.CoverageSpec spec = CoverageRules.validate(request,
                policy.premiumCurrency(), policy.effectiveDate(), policy.expirationDate());
        UUID coverageId = UUID.randomUUID();
        String requestHash = IdempotencyKeys.requestHash(policyId, request);
        CommandResult<CoverageView> result = idempotency.run(tenantId, policyId, "coverage",
                rawIdempotencyKey, requestHash, coverageId,
                id -> applyCreate(policy, spec, id),
                id -> loadView(tenantId, policyId, id));
        Policy refreshed = requirePolicy(tenantId, policyId);
        return new CoverageResult(result.value(), refreshed.version(), result.replayed());
    }

    /**
     * Applies one erosion fact. Replaying the same fact is a no-op (natural-key
     * idempotency); an older fact for a chain that already has a newer one is a
     * 409; a fact that would take the limit negative is 422 with the available
     * amount in {@code meta}.
     */
    @Transactional
    public ErosionResult applyErosion(UUID tenantId, UUID policyId, ErosionRequest request,
                                      String rawIdempotencyKey) {
        Policy policy = guard.requirePolicy(tenantId, policyId, null);
        PolicyLimit limit = coverageRepository.findLimit(tenantId, policyId, request.limitId())
                .orElseThrow(() -> PolicyErrors.notFound("LIMIT_NOT_FOUND", "limitId",
                        "Policy limit not found: " + request.limitId()));
        PolicyLimitState existing = coverageRepository.findState(tenantId, policyId, limit.id())
                .orElseThrow(() -> PolicyErrors.notFound("LIMIT_STATE_NOT_FOUND", "limitId",
                        "The limit state is missing for limit " + limit.id()));
        boolean naturalReplay = coverageRepository.erosionFactExists(limit.id(), request.claimId(),
                request.allocationId(), request.sourceVersion());
        if (naturalReplay) {
            return new ErosionResult(existing, policy.version(), true);
        }
        String requestHash = IdempotencyKeys.requestHash(policyId, request);
        CommandResult<PolicyLimitState> result = idempotency.run(tenantId, policyId, "erosion",
                rawIdempotencyKey, requestHash, limit.id(),
                id -> applyErosionFact(policy, limit, request),
                id -> coverageRepository.findState(tenantId, policyId, id).orElse(null));
        Policy refreshed = requirePolicy(tenantId, policyId);
        return new ErosionResult(result.value(), refreshed.version(), result.replayed());
    }

    private CoverageView applyCreate(Policy policy, CoverageRules.CoverageSpec spec, UUID coverageId) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        if (coverageRepository.coverageCodeExists(tenantId, policyId, spec.coverageCode())) {
            throw PolicyErrors.conflict("DUPLICATE_COVERAGE_CODE", "coverageCode",
                    "An active coverage with code " + spec.coverageCode() + " already exists.");
        }
        UUID limitId = UUID.randomUUID();
        UUID stateId = UUID.randomUUID();
        int sequence = coverageRepository.nextCoverageSequence(tenantId, policyId);
        Instant now = Instant.now();
        PolicyCoverage coverage = new PolicyCoverage(coverageId, tenantId, policyId,
                spec.coverageCode(), spec.coverageName(), spec.coveragePart(),
                spec.occurrenceBasis(), spec.coinsurancePct(), spec.effectiveFrom(),
                spec.effectiveTo(), "ACTIVE", sequence, now);
        CoverageRules.LimitSpec limitSpec = spec.limit();
        PolicyLimit limit = new PolicyLimit(limitId, tenantId, policyId, coverageId,
                limitSpec.perOccurrenceAmount(), limitSpec.aggregateAmount(),
                limitSpec.aggregateBasis(), limitSpec.sublimitAmount(), limitSpec.deductibleAmount(),
                limitSpec.coinsurancePct(), limitSpec.currency(), limitSpec.erosionBasis(),
                spec.effectiveFrom(), spec.effectiveTo(), sequence, now);
        BigDecimal base = limit.erosionBase();
        PolicyLimitState state = new PolicyLimitState(stateId, tenantId, policyId, limitId, now,
                base, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY, base, ZERO_PERCENT,
                ZERO_MONEY, 0L, now, "CURRENT", 0);
        coverageRepository.insertCoverage(coverage);
        coverageRepository.insertLimit(limit);
        coverageRepository.insertState(state);
        if (versionService.isIssued(tenantId, policyId)) {
            UUID versionId = UUID.randomUUID();
            PolicyVersion current = versionService.currentOrNull(tenantId, policyId);
            LocalDate versionDate = VersionTimeline.resolve(spec.effectiveFrom(), policy, current,
                    null, null, null);
            endorsementFactory.create(UUID.randomUUID(), policy, versionId, null, "COVERAGE",
                    spec.effectiveFrom(), "Coverage added: " + spec.coverageCode(), ZERO_MONEY,
                    policy.premiumCurrency(), "BROADEN",
                    List.of(new EndorsementCoverageChange(spec.coverageCode(), "ADD",
                            spec.coverageName())), null, null, null, null, null, false);
            versionIssuer.issue(policy, versionId, "ENDORSEMENT", versionDate,
                    "Coverage added: " + spec.coverageCode(), ZERO_MONEY,
                    policy.premiumCurrency(), null);
        }
        touch(policy);
        return new CoverageView(coverage, limit, state);
    }

    private PolicyLimitState applyErosionFact(Policy policy, PolicyLimit limit, ErosionRequest request) {
        UUID tenantId = policy.tenantId();
        UUID policyId = policy.id();
        if (coverageRepository.chainHasNewerFact(limit.id(), request.claimId(),
                request.allocationId(), request.sourceVersion())) {
            throw PolicyErrors.conflict("EROSION_STALE_VERSION", "sourceVersion",
                    "A newer erosion fact already exists for this claim allocation; sourceVersion "
                            + request.sourceVersion() + " is stale.");
        }
        PolicyLimitState state = coverageRepository.findState(tenantId, policyId, limit.id())
                .orElseThrow(() -> PolicyErrors.notFound("LIMIT_STATE_NOT_FOUND", "limitId",
                        "The limit state is missing for limit " + limit.id()));
        Instant asOf = request.asOf() == null ? Instant.now() : request.asOf();
        coverageRepository.insertErosionFact(UUID.randomUUID(), tenantId, policyId, limit.id(),
                request.claimId(), request.allocationId(), request.sourceVersion(),
                request.paidAmount(), request.reservedAmount(),
                request.recoveredAmount() == null ? ZERO_MONEY : request.recoveredAmount(),
                asOf, Instant.now());
        CoverageRepository.ErosionTotals totals = coverageRepository.sumLatestFacts(tenantId,
                policyId, limit.id());
        BigDecimal paid = money(totals.paid());
        BigDecimal reserved = money(totals.reserved());
        BigDecimal recovered = money(totals.recovered());
        BigDecimal incurred = paid.add(reserved).setScale(4, RoundingMode.HALF_UP);
        BigDecimal remaining = state.originalAmount().add(state.reinstatedAmount())
                .subtract(incurred).add(recovered).setScale(4, RoundingMode.HALF_UP);
        if (remaining.signum() < 0) {
            String message = "The erosion fact would exceed the limit by "
                    + remaining.abs().toPlainString() + "; available amount is "
                    + state.remainingAmount().toPlainString() + ".";
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED", message,
                    List.of(new ApiException.FieldIssue("paidAmount", "LIMIT_EXCEEDED", message)),
                    Map.of("limitId", limit.id().toString(),
                            "availableAmount", money(state.remainingAmount()).toPlainString(),
                            "currency", limit.currency()));
        }
        BigDecimal erodedPct = state.originalAmount().signum() == 0
                ? ZERO_PERCENT
                : incurred.divide(state.originalAmount(), 6, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        PolicyLimitState next = new PolicyLimitState(state.id(), tenantId, policyId, limit.id(),
                asOf, state.originalAmount(), paid, reserved, incurred, recovered, remaining,
                erodedPct, state.reinstatedAmount(), totals.maxSourceVersion(), Instant.now(),
                "CURRENT", state.version());
        int rows = coverageRepository.updateState(next, state.version());
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "limitId",
                    "The limit state changed concurrently; reload and retry.");
        }
        return coverageRepository.findState(tenantId, policyId, limit.id()).orElse(next);
    }

    private CoverageView loadView(UUID tenantId, UUID policyId, UUID coverageId) {
        PolicyCoverage coverage = coverageRepository.findCoverage(tenantId, policyId, coverageId)
                .orElse(null);
        if (coverage == null) {
            return null;
        }
        PolicyLimit limit = coverageRepository.findLimitByCoverage(tenantId, policyId, coverageId)
                .orElse(null);
        PolicyLimitState state = limit == null
                ? null
                : coverageRepository.findState(tenantId, policyId, limit.id()).orElse(null);
        return new CoverageView(coverage, limit, state);
    }

    private void touch(Policy policy) {
        int rows = policyRepository.touch(policy.tenantId(), policy.id(), policy.version());
        if (rows == 0) {
            throw PolicyErrors.conflict("CONCURRENT_MODIFICATION", "If-Match",
                    "The policy changed concurrently; reload and retry.");
        }
    }

    private Policy requirePolicy(UUID tenantId, UUID policyId) {
        return policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_UP);
    }
}
