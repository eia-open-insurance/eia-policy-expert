package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);

    @Test
    void normalizesOptionalKey() {
        assertThat(IdempotencyKeys.normalize(null)).isNull();
        assertThat(IdempotencyKeys.normalize("   ")).isNull();
        assertThat(IdempotencyKeys.normalize("  key-1 ")).isEqualTo("key-1");
    }

    @Test
    void rejectsOverlongKey() {
        assertThatThrownBy(() -> IdempotencyKeys.normalize("k".repeat(256)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void sameBusinessRequestHashesIdenticallyAcrossScaleAndWhitespace() {
        CreatePolicyRequest first = request("  ", "CGL", new BigDecimal("125000.0"),
                new BigDecimal("2000000"));
        CreatePolicyRequest second = request(null, " CGL ", new BigDecimal("125000.0000"),
                new BigDecimal("2000000.0000"));

        assertThat(IdempotencyKeys.requestHash(first))
                .isEqualTo(IdempotencyKeys.requestHash(second))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void differentRequestHashesDifferently() {
        CreatePolicyRequest base = request(null, "CGL", new BigDecimal("125000.00"),
                new BigDecimal("2000000.00"));
        CreatePolicyRequest changedPremium = new CreatePolicyRequest(TENANT, null, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, new BigDecimal("125001.00"), "USD",
                new BigDecimal("2000000.00"), "USD");

        assertThat(IdempotencyKeys.requestHash(base))
                .isNotEqualTo(IdempotencyKeys.requestHash(changedPremium));
    }

    private static CreatePolicyRequest request(String policyNumber, String productCode,
                                               BigDecimal premium, BigDecimal limit) {
        return new CreatePolicyRequest(TENANT, policyNumber, productCode, PARTY, null,
                EFFECTIVE, EXPIRATION, premium, "USD", limit, "USD");
    }
}
