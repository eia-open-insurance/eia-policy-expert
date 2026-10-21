package com.eia.policy.expert;

import java.util.UUID;

/**
 * Assign or clear the legal entity of a policy. A null {@code legalEntityId}
 * clears the link; a missing body is treated the same way by the service.
 */
public record AssignPolicyLegalEntityRequest(UUID legalEntityId) {
}
