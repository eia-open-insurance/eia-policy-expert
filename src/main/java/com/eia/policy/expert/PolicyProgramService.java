package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Commercial program catalog and policy-to-program assignment.
 *
 * <p>A program is created ACTIVE with a server-generated program number. Every
 * accepted create writes the row and {@code policy.program.created} in one
 * transaction. Assignment is a policy command: it validates both sides of the
 * tenant wall, bumps the policy aggregate version, writes
 * {@code policy.policy.program-assigned}, and returns the updated policy. A null
 * program id clears the link.</p>
 */
@Service
public class PolicyProgramService {
    private final PolicyRepository policyRepository;
    private final PolicyProgramRepository programRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public PolicyProgramService(PolicyRepository policyRepository,
                                PolicyProgramRepository programRepository,
                                OutboxRepository outbox) {
        this(policyRepository, programRepository, outbox, Clock.systemUTC());
    }

    PolicyProgramService(PolicyRepository policyRepository,
                         PolicyProgramRepository programRepository,
                         OutboxRepository outbox, Clock clock) {
        this.policyRepository = policyRepository;
        this.programRepository = programRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Creates a program. The request is validated and normalized before the
     * number sequence is consumed, so a rejected body never burns a number.
     */
    @Transactional
    public ProgramView create(UUID tenantId, CreateProgramRequest request) {
        ProgramRules.ProgramSpec spec = ProgramRules.validateCreate(request);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        PolicyProgram program = new PolicyProgram(UUID.randomUUID(), tenantId,
                programRepository.nextProgramNumber(), spec.name(), spec.programType(),
                spec.effectiveFrom(), spec.effectiveTo(), ProgramRules.ACTIVE,
                spec.leadPolicyId(), now);
        programRepository.insert(program);
        outbox.insert(OutboxEvent.fromEnvelope(PolicyEvents.programCreated(program, now)));
        return ProgramView.of(program);
    }

    public List<ProgramView> list(UUID tenantId) {
        return programRepository.findAll(tenantId).stream().map(ProgramView::of).toList();
    }

    public ProgramView get(UUID tenantId, UUID programId) {
        return ProgramView.of(requireProgram(tenantId, programId));
    }

    /**
     * Assigns the policy to a program or clears it. The policy row update and
     * the outbox event commit together; a concurrent policy change is a
     * canonical 409 rather than a lost update.
     */
    @Transactional
    public Policy assignProgram(UUID tenantId, UUID policyId,
                                AssignPolicyProgramRequest request) {
        Policy before = policyRepository.findById(tenantId, policyId)
                .orElseThrow(() -> PolicyErrors.notFound("POLICY_NOT_FOUND", "policyId",
                        "Policy not found: " + policyId));
        UUID programId = request == null ? null : request.programId();
        if (programId != null) {
            requireProgram(tenantId, programId);
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int rows = policyRepository.assignProgram(tenantId, policyId, programId,
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
        outbox.insert(OutboxEvent.fromEnvelope(PolicyEvents.programAssigned(before, after, now)));
        return after;
    }

    private PolicyProgram requireProgram(UUID tenantId, UUID programId) {
        return programRepository.findById(tenantId, programId)
                .orElseThrow(() -> PolicyErrors.notFound("PROGRAM_NOT_FOUND", "programId",
                        "Program not found: " + programId));
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
