package com.eia.policy.expert;

import java.util.UUID;

/**
 * Assign or clear the program of a policy. A null {@code programId} clears the
 * link; a missing body is treated the same way by the service.
 */
public record AssignPolicyProgramRequest(UUID programId) {
}
