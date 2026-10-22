package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final Instant CREATED = Instant.parse("2026-10-19T12:00:00Z");

    @Mock
    private PolicyRepository repository;

    @Mock
    private PolicyVersionService versionService;

    @Mock
    private ProductCatalogGuard productCatalog;

    @Mock
    private OutboxRepository outbox;

    private PolicyService service() {
        return new PolicyService(repository, versionService, productCatalog, outbox);
    }

    @Test
    void storesIdempotencyKeyInTheSameTransactionBeforeCreatingThePolicy() {
        when(repository.findIdempotency(TENANT, "idem-1")).thenReturn(Optional.empty());
        when(repository.insertIdempotencyIfAbsent(eq(TENANT), eq("idem-1"), anyString(), any(UUID.class)))
                .thenReturn(true);
        when(repository.create(any(CreatePolicyRequest.class), any(UUID.class)))
                .thenReturn(policy("DRAFT", 0));

        CreateResult result = service().create(request(), "idem-1");

        assertThat(result.replayed()).isFalse();
        assertThat(result.policy().status()).isEqualTo("DRAFT");
        InOrder order = inOrder(repository);
        order.verify(repository).insertIdempotencyIfAbsent(
                eq(TENANT), eq("idem-1"), anyString(), any(UUID.class));
        order.verify(repository).create(any(CreatePolicyRequest.class), any(UUID.class));
    }

    @Test
    void replaysOriginalPolicyForSameKeyAndSameRequest() {
        CreatePolicyRequest request = request();
        when(repository.findIdempotency(TENANT, "idem-1"))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        POLICY_ID, IdempotencyKeys.requestHash(request))));
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 0)));

        CreateResult result = service().create(request, "idem-1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.policy().id()).isEqualTo(POLICY_ID);
        verify(repository, never()).create(any(CreatePolicyRequest.class), any(UUID.class));
    }

    @Test
    void rejectsSameKeyWithDifferentRequest() {
        CreatePolicyRequest request = request();
        when(repository.findIdempotency(TENANT, "idem-1"))
                .thenReturn(Optional.of(new IdempotencyRecord(POLICY_ID, "different-hash")));

        assertThatThrownBy(() -> service().create(request, "idem-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(api.meta()).containsEntry("existingPolicyId", POLICY_ID.toString());
                });

        verify(repository, never()).create(any(CreatePolicyRequest.class), any(UUID.class));
    }

    @Test
    void replaysWhenAConcurrentRequestWonTheKeyReservation() {
        CreatePolicyRequest request = request();
        IdempotencyRecord winner = new IdempotencyRecord(
                POLICY_ID, IdempotencyKeys.requestHash(request));
        when(repository.findIdempotency(TENANT, "idem-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.insertIdempotencyIfAbsent(eq(TENANT), eq("idem-1"), anyString(), any(UUID.class)))
                .thenReturn(false);
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 0)));

        CreateResult result = service().create(request, "idem-1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.policy().id()).isEqualTo(POLICY_ID);
        verify(repository, never()).create(any(CreatePolicyRequest.class), any(UUID.class));
    }

    @Test
    void createsWithoutKeyAndDoesNotTouchIdempotencyStorage() {
        when(repository.create(any(CreatePolicyRequest.class), any(UUID.class)))
                .thenReturn(policy("DRAFT", 0));

        CreateResult result = service().create(request(), null);

        assertThat(result.replayed()).isFalse();
        verify(repository).create(any(CreatePolicyRequest.class), any(UUID.class));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void checksTheProductCatalogBeforeCreatingANewPolicy() {
        when(repository.create(any(CreatePolicyRequest.class), any(UUID.class)))
                .thenReturn(policy("DRAFT", 0));

        service().create(request(), null);

        verify(productCatalog).requireActive(TENANT, "CGL");
    }

    @Test
    void aRetiredProductRejectionCreatesNoPolicy() {
        ApiException rejected = PolicyErrors.businessRule("PRODUCT_NOT_ACTIVE", "productCode",
                "Product code CGL is RETIRED; only an ACTIVE product can be used for new business.");
        doThrow(rejected).when(productCatalog).requireActive(TENANT, "CGL");

        assertThatThrownBy(() -> service().create(request(), null)).isSameAs(rejected);

        verify(repository, never()).create(any(CreatePolicyRequest.class), any(UUID.class));
    }

    @Test
    void anIdempotentCreateReplayDoesNotRecheckTheCatalog() {
        CreatePolicyRequest request = request();
        when(repository.findIdempotency(TENANT, "idem-1"))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        POLICY_ID, IdempotencyKeys.requestHash(request))));
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 0)));

        service().create(request, "idem-1");

        verifyNoInteractions(productCatalog);
    }

    @Test
    void conversionCreatesWithoutRecheckingTheCatalog() {
        when(repository.create(any(CreatePolicyRequest.class), any(UUID.class)))
                .thenReturn(policy("DRAFT", 0));

        Policy created = service().createConvertedPolicy(request());

        assertThat(created.status()).isEqualTo("DRAFT");
        verifyNoInteractions(productCatalog);
        verify(versionService).createInitialVersion(eq(created), any(UUID.class));
    }

    @Test
    void rejectsStaleIfMatchBeforeUpdating() {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 2)));

        assertThatThrownBy(() -> service().updateStatus(
                TENANT, POLICY_ID, new UpdatePolicyStatusRequest("QUOTED", 0, null), "\"1\""))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CONCURRENT_MODIFICATION");
                    assertThat(api.meta()).containsEntry("currentVersion", 2)
                            .containsEntry("expectedVersion", 1);
                });

        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyInt());
    }

    @Test
    void wildcardIfMatchUsesTheCurrentVersion() {
        when(repository.findById(TENANT, POLICY_ID)).thenReturn(Optional.of(policy("DRAFT", 2)));
        when(repository.updateStatus(TENANT, POLICY_ID, "QUOTED", null, 2))
                .thenReturn(policy("QUOTED", 3));

        Policy result = service().updateStatus(
                TENANT, POLICY_ID, new UpdatePolicyStatusRequest("QUOTED", 0, null), "*");

        assertThat(result.status()).isEqualTo("QUOTED");
        verify(repository).updateStatus(TENANT, POLICY_ID, "QUOTED", null, 2);
    }

    @Test
    void refusesToIssueWithoutANamedInsured() {
        when(repository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(policy("BOUND", 3)));
        when(repository.countNamedInsured(TENANT, POLICY_ID)).thenReturn(0);

        assertThatThrownBy(() -> service().updateStatus(
                TENANT, POLICY_ID, new UpdatePolicyStatusRequest("ISSUED", 3, null), "\"3\""))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("NAMED_INSURED_REQUIRED");
                });

        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyInt());
        verify(versionService, never()).issuePolicyVersion(any(), any());
    }

    @Test
    void issuingAPolicyIssuesThePendingVersion() {
        when(repository.findById(TENANT, POLICY_ID))
                .thenReturn(Optional.of(policy("BOUND", 3)));
        when(repository.countNamedInsured(TENANT, POLICY_ID)).thenReturn(1);
        when(repository.updateStatus(TENANT, POLICY_ID, "ISSUED", null, 3))
                .thenReturn(policy("ISSUED", 4));

        Policy result = service().updateStatus(
                TENANT, POLICY_ID, new UpdatePolicyStatusRequest("ISSUED", 3, null), "\"3\"");

        assertThat(result.status()).isEqualTo("ISSUED");
        verify(versionService).issuePolicyVersion(result, null);
    }

    @Test
    void creatingAPolicyStoresItsInitialVersion() {
        when(repository.create(any(CreatePolicyRequest.class), any(UUID.class)))
                .thenReturn(policy("DRAFT", 0));

        Policy created = service().create(request());

        assertThat(created.status()).isEqualTo("DRAFT");
        verify(versionService).createInitialVersion(eq(created), any(UUID.class));
    }

    private static CreatePolicyRequest request() {
        return new CreatePolicyRequest(TENANT, null, "CGL", PARTY, null, EFFECTIVE, EXPIRATION,
                new BigDecimal("125000.00"), "USD", new BigDecimal("2000000.00"), "USD");
    }

    private static Policy policy(String status, int version) {
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", PARTY, status,
                EFFECTIVE, EXPIRATION, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", version, CREATED, CREATED);
    }
}
