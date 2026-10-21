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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Legal entity catalog and policy-to-legal-entity assignment.
 *
 * <p>A legal entity is created ACTIVE, with its code normalized upper-case and
 * unique per tenant. Every accepted create writes the row and
 * {@code policy.legal-entity.created} in one transaction. Assignment is a policy
 * command: it validates both sides of the tenant wall, bumps the policy
 * aggregate version, writes {@code policy.policy.legal-entity-assigned}, and
 * returns the updated policy. A null legal entity id clears the link.</p>
 */
@Service
public class LegalEntityService {
    private final PolicyRepository policyRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public LegalEntityService(PolicyRepository policyRepository,
                              LegalEntityRepository legalEntityRepository,
                              OutboxRepository outbox) {
        this(policyRepository, legalEntityRepository, outbox, Clock.systemUTC());
    }

    LegalEntityService(PolicyRepository policyRepository,
                       LegalEntityRepository legalEntityRepository,
                       OutboxRepository outbox, Clock clock) {
        this.policyRepository = policyRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Creates a legal entity. The request is validated and normalized before
     * the row is written, so a rejected body never leaves data behind.
     */
    @Transactional
    public LegalEntityView create(UUID tenantId, CreateLegalEntityRequest request) {
        LegalEntityRules.LegalEntitySpec spec = LegalEntityRules.validateCreate(request);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        LegalEntity legalEntity = new LegalEntity(UUID.randomUUID(), tenantId, spec.code(),
                spec.name(), spec.entityType(), spec.jurisdiction(), spec.regulator(),
                spec.licenseNumber(), LegalEntityRules.ACTIVE, now);
        try {
            legalEntityRepository.insert(legalEntity);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_LEGAL_ENTITY_CODE",
                    "Legal entity code already exists for this tenant: " + spec.code(),
                    List.of(new ApiException.FieldIssue("code", "DUPLICATE_CODE",
                            "Legal entity code already exists for this tenant: "
                                    + spec.code())));
        }
        outbox.insert(OutboxEvent.fromEnvelope(PolicyEvents.legalEntityCreated(legalEntity, now)));
        return LegalEntityView.of(legalEntity);
    }

    public List<LegalEntityView> list(UUID tenantId) {
        return legalEntityRepository.findAll(tenantId).stream()
                .map(LegalEntityView::of)
                .toList();
    }

    public LegalEntityView get(UUID tenantId, UUID legalEntityId) {
        return LegalEntityView.of(requireLegalEntity(tenantId, legalEntityId));
    }

    /**
     * Assigns the policy to a legal entity or clears it. The policy row update
     * and the outbox event commit together; a concurrent policy change is a
     * canonical 409 rather than a lost update.
     */
    @Transactional
    public Policy assignLegalEntity(UUID tenantId, UUID policyId,
                                    AssignPolicyLegalEntityRequest request) {
        Policy before = policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
        UUID legalEntityId = request == null ? null : request.legalEntityId();
        if (legalEntityId != null) {
            requireLegalEntity(tenantId, legalEntityId);
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int rows = policyRepository.assignLegalEntity(tenantId, policyId, legalEntityId,
                before.version(), now);
        if (rows == 0) {
            int currentVersion = policyRepository.findById(tenantId, policyId)
                    .map(Policy::version)
                    .orElse(before.version());
            throw conflict(currentVersion, before.version());
        }

        Policy after = policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
        outbox.insert(OutboxEvent.fromEnvelope(
                PolicyEvents.legalEntityAssigned(before, after, now)));
        return after;
    }

    private LegalEntity requireLegalEntity(UUID tenantId, UUID legalEntityId) {
        return legalEntityRepository.findById(tenantId, legalEntityId)
                .orElseThrow(() -> PolicyErrors.notFound("LEGAL_ENTITY_NOT_FOUND",
                        "legalEntityId", "Legal entity not found: " + legalEntityId));
    }

    private static ApiException conflict(int currentVersion, int expectedVersion) {
        String message = "Expected version " + expectedVersion + " but policy is at version "
                + currentVersion + "; reload and retry";
        return new ApiException(HttpStatus.CONFLICT,
                "CONCURRENT_MODIFICATION", message,
                List.of(new ApiException.FieldIssue(
                        "expectedVersion", "STALE_VERSION", message)),
                Map.of("currentVersion", currentVersion, "expectedVersion", expectedVersion));
    }
}
