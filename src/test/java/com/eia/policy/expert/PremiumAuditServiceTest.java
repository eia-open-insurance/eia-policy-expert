package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PremiumAuditServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);
    private static final LocalDate AUDIT_DATE = LocalDate.of(2026, 6, 1);

    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private MutationGuard guard;
    @Mock
    private EndorsementFactory endorsementFactory;
    @Mock
    private PolicyVersionService versionService;
    @Mock
    private VersionIssuer versionIssuer;
    @Mock
    private OutboxRepository outbox;

    @Test
    void refusesAnIneligiblePolicyStatusWithoutWriting() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("DRAFT", "120000.0000"));

        assertThatThrownBy(() -> service().audit(TENANT, POLICY_ID, request("150000.00"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("PREMIUM_AUDIT_NOT_ALLOWED");
                    assertThat(api.meta()).containsEntry("sourceStatus", "DRAFT");
                });

        verify(endorsementFactory, never()).create(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(policyRepository, never()).updatePremium(any(), any(), any(), anyInt());
    }

    @Test
    void rejectsANegativeAuditedPremium() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE", "120000.0000"));

        assertThatThrownBy(() -> service().audit(TENANT, POLICY_ID, request("-1.00"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("PREMIUM_AUDIT_PREMIUM_INVALID");
                });
    }

    @Test
    void requiresAReason() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE", "120000.0000"));

        PremiumAuditRequest request = new PremiumAuditRequest(new BigDecimal("150000.00"),
                AUDIT_DATE, "   ", null, null);

        assertThatThrownBy(() -> service().audit(TENANT, POLICY_ID, request, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("PREMIUM_AUDIT_REASON_REQUIRED");
                });
    }

    @Test
    void rejectsANoOpAuditEvenWhenTheScalesDiffer() {
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(policy("IN_FORCE", "120000.0000"));

        assertThatThrownBy(() -> service().audit(TENANT, POLICY_ID,
                new PremiumAuditRequest(new BigDecimal("120000"), AUDIT_DATE, "Annual audit",
                        null, null), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("NO_PREMIUM_CHANGE");
                });

        verify(endorsementFactory, never()).create(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(policyRepository, never()).updatePremium(any(), any(), any(), anyInt());
        verify(outbox, never()).insert(any());
    }

    @Test
    void appliesAnIncreaseAndWritesTheAuditVersionAndEvents() {
        Policy before = policy("IN_FORCE", "120000.0000");
        Policy after = policy("IN_FORCE", "150000.0000");
        after = new Policy(after.id(), after.tenantId(), after.policyNumber(), after.productCode(),
                after.insuredPartyId(), after.status(), after.effectiveDate(), after.expirationDate(),
                after.premiumAmount(), after.premiumCurrency(), after.limitAmount(),
                after.limitCurrency(), 5, after.createdAt(), after.updatedAt());
        PolicyVersion version = version(new BigDecimal("30000.0000"));
        String description = "Premium audit: 120000.0000 -> 150000.0000 (30000.0000)";

        when(guard.requirePolicy(TENANT, POLICY_ID, "\"4\"")).thenReturn(before);
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(version);
        when(policyRepository.updatePremium(TENANT, POLICY_ID, new BigDecimal("150000.0000"), 4))
                .thenReturn(1);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(after));
        when(versionIssuer.issue(eq(after), any(UUID.class), eq("PREMIUM_AUDIT"), eq(AUDIT_DATE),
                eq(description), eq(new BigDecimal("30000.0000")), eq("USD"), isNull()))
                .thenReturn(version);

        Policy result = service().audit(TENANT, POLICY_ID, request("150000.00"), "\"4\"");

        assertThat(result).isEqualTo(after);
        verify(endorsementFactory).create(any(UUID.class), eq(before), any(UUID.class), isNull(),
                eq("PREMIUM_AUDIT"), eq(AUDIT_DATE), eq(description),
                eq(new BigDecimal("30000.0000")), eq("USD"), eq("NONE"), anyList(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(false));
        verify(versionIssuer).issue(eq(after), any(UUID.class), eq("PREMIUM_AUDIT"),
                eq(AUDIT_DATE), eq(description), eq(new BigDecimal("30000.0000")), eq("USD"),
                isNull());

        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, times(2)).insert(events.capture());
        assertThat(events.getAllValues()).extracting(OutboxEvent::eventType)
                .containsExactly("policy.policy.premium-audited", "policy.policy.version-issued");
        Map<String, Object> data = events.getAllValues().getFirst().toEnvelope().data();
        assertThat(data)
                .containsEntry("policyId", POLICY_ID.toString())
                .containsEntry("policyNumber", "POL-2026-0000001")
                .containsEntry("previousPremium", "120000.0000")
                .containsEntry("auditedPremium", "150000.0000")
                .containsEntry("delta", "30000.0000")
                .containsEntry("currency", "USD")
                .containsEntry("effectiveDate", "2026-06-01")
                .containsEntry("reason", "Annual exposure audit");
    }

    @Test
    void appliesADecreaseWithANegativeDelta() {
        Policy before = policy("ENDORSED", "120000.0000");
        Policy after = policy("ENDORSED", "100000.0000");
        PolicyVersion version = version(new BigDecimal("-20000.0000"));
        String description = "Premium audit: 120000.0000 -> 100000.0000 (-20000.0000)";

        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(before);
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(null);
        when(policyRepository.updatePremium(TENANT, POLICY_ID, new BigDecimal("100000.0000"), 4))
                .thenReturn(1);
        when(policyRepository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(after));
        when(versionIssuer.issue(eq(after), any(UUID.class), eq("PREMIUM_AUDIT"), eq(AUDIT_DATE),
                eq(description), eq(new BigDecimal("-20000.0000")), eq("USD"), isNull()))
                .thenReturn(version);

        service().audit(TENANT, POLICY_ID, request("100000.00"), null);

        verify(versionIssuer).issue(eq(after), any(UUID.class), eq("PREMIUM_AUDIT"),
                eq(AUDIT_DATE), eq(description), eq(new BigDecimal("-20000.0000")), eq("USD"),
                isNull());
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, times(2)).insert(events.capture());
        assertThat(events.getAllValues().getFirst().toEnvelope().data())
                .containsEntry("delta", "-20000.0000")
                .containsEntry("auditedPremium", "100000.0000");
    }

    @Test
    void requiresApprovalForABackdatedAuditBeforeInception() {
        Policy before = policy("IN_FORCE", "120000.0000");
        when(guard.requirePolicy(TENANT, POLICY_ID, null)).thenReturn(before);
        when(versionService.currentOrNull(TENANT, POLICY_ID)).thenReturn(null);
        PremiumAuditRequest request = new PremiumAuditRequest(new BigDecimal("150000.00"),
                FROM.minusDays(1), "Retroactive audit", null, null);

        assertThatThrownBy(() -> service().audit(TENANT, POLICY_ID, request, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(api.code()).isEqualTo("APPROVAL_REQUIRED");
                });
    }

    private PremiumAuditService service() {
        return new PremiumAuditService(policyRepository, guard, endorsementFactory, versionService,
                versionIssuer, outbox);
    }

    private static PremiumAuditRequest request(String auditedPremium) {
        return new PremiumAuditRequest(new BigDecimal(auditedPremium), AUDIT_DATE,
                "Annual exposure audit", null, null);
    }

    private static Policy policy(String status, String premium) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), status, FROM, TO,
                new BigDecimal(premium), "USD", new BigDecimal("2000000.0000"), "USD",
                4, now, now);
    }

    private static PolicyVersion version(BigDecimal delta) {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, 2, "POL-2026-0000001-02",
                "PREMIUM_AUDIT", "ISSUED", AUDIT_DATE, null, now, null, null, null,
                delta, "USD", "Premium audit", null, "hash", now);
    }
}
