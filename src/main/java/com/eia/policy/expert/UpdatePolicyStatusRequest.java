package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePolicyStatusRequest(
        @NotBlank String status,
        Integer expectedVersion,
        @Size(max = 200) String reason
) {}
