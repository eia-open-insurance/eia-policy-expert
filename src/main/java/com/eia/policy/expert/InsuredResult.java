package com.eia.policy.expert;

/** Result of an insured command. */
public record InsuredResult(PolicyInsured insured, int policyVersion, boolean replayed) {
}
