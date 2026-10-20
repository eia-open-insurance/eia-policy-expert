package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Renewal rules: eligible source states, inherited defaults, explicit
 * overrides, and the term date invariants.
 */
class PolicyRenewalRulesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final Instant CREATED = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void acceptsEveryRenewableSourceStatus() {
        for (String status : List.of("ISSUED", "IN_FORCE", "ENDORSED", "REINSTATED")) {
            PolicyRenewalRules.RenewalTerms terms = PolicyRenewalRules.validate(
                    policy(status, null),
                    new RenewPolicyRequest(null, EXPIRATION.plusYears(1), null, null, null, null));

            assertThat(terms.effectiveDate()).isEqualTo(EXPIRATION.plusDays(1));
            assertThat(terms.expirationDate()).isEqualTo(EXPIRATION.plusYears(1));
        }
    }

    @Test
    void rejectsEveryBlockedSourceStatusWithConflictAndMeta() {
        for (String status : List.of("DRAFT", "QUOTED", "BOUND", "RENEWED", "CANCELLED",
                "EXPIRED", "NON_RENEWED", "VOID")) {
            assertThatThrownBy(() -> PolicyRenewalRules.validate(
                    policy(status, null),
                    new RenewPolicyRequest(null, EXPIRATION.plusYears(1), null, null, null, null)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(api.code()).isEqualTo("RENEWAL_NOT_ALLOWED");
                        assertThat(api.meta())
                                .containsEntry("sourcePolicyId", POLICY_ID.toString())
                                .containsEntry("sourceStatus", status);
                        assertThat(api.meta().get("allowedStatuses"))
                                .isEqualTo(List.of("ENDORSED", "IN_FORCE", "ISSUED", "REINSTATED"));
                    });
        }
    }

    @Test
    void inheritsTheSourceTermWhenOnlyExpirationIsProvided() {
        Policy source = policy("IN_FORCE", null);

        PolicyRenewalRules.RenewalTerms terms = PolicyRenewalRules.validate(source,
                new RenewPolicyRequest(null, EXPIRATION.plusYears(1), null, null, null, null));

        assertThat(terms.effectiveDate()).isEqualTo(EXPIRATION.plusDays(1));
        assertThat(terms.premiumAmount()).isEqualByComparingTo("125000.0000");
        assertThat(terms.premiumCurrency()).isEqualTo("USD");
        assertThat(terms.limitAmount()).isEqualByComparingTo("2000000.0000");
        assertThat(terms.limitCurrency()).isEqualTo("USD");
    }

    @Test
    void explicitTermsOverrideTheInheritedDefaults() {
        Policy source = policy("ENDORSED", null);

        PolicyRenewalRules.RenewalTerms terms = PolicyRenewalRules.validate(source,
                new RenewPolicyRequest(EXPIRATION, EXPIRATION.plusYears(2),
                        new BigDecimal("99000.00"), "EUR", new BigDecimal("3000000.00"), "EUR"));

        assertThat(terms.effectiveDate()).isEqualTo(EXPIRATION);
        assertThat(terms.expirationDate()).isEqualTo(EXPIRATION.plusYears(2));
        assertThat(terms.premiumAmount()).isEqualByComparingTo("99000.00");
        assertThat(terms.premiumCurrency()).isEqualTo("EUR");
        assertThat(terms.limitAmount()).isEqualByComparingTo("3000000.00");
        assertThat(terms.limitCurrency()).isEqualTo("EUR");
    }

    @Test
    void rejectsAnEffectiveDateBeforeTheSourceExpiration() {
        assertThatThrownBy(() -> PolicyRenewalRules.validate(policy("ISSUED", null),
                new RenewPolicyRequest(EXPIRATION.minusDays(1), EXPIRATION.plusYears(1),
                        null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .contains("effectiveDate");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::code)
                            .contains("INVALID_RENEWAL_EFFECTIVE_DATE");
                });
    }

    @Test
    void rejectsAnExpirationThatIsNotAfterTheEffectiveDate() {
        assertThatThrownBy(() -> PolicyRenewalRules.validate(policy("ISSUED", null),
                new RenewPolicyRequest(EXPIRATION.plusDays(5), EXPIRATION.plusDays(5),
                        null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .contains("expirationDate");
                });
    }

    @Test
    void rejectsNegativeRenewalAmounts() {
        assertThatThrownBy(() -> PolicyRenewalRules.validate(policy("ISSUED", null),
                new RenewPolicyRequest(null, EXPIRATION.plusYears(1),
                        new BigDecimal("-1"), null, new BigDecimal("-1"), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactlyInAnyOrder("premiumAmount", "limitAmount");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::code)
                            .containsOnly("MIN_VALUE");
                });
    }

    private static Policy policy(String status, UUID renewedFromPolicyId) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", PARTY, status,
                EFFECTIVE, EXPIRATION, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", 3, CREATED, CREATED,
                renewedFromPolicyId);
    }
}
