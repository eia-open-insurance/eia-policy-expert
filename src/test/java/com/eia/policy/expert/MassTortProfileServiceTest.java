package com.eia.policy.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end mass tort profile slice against H2: create and update upserts,
 * reads and 404s, COMPLETE gating, tenant wall, the outbox payload, and
 * transactional rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class MassTortProfileServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final LocalDate BLOCK_START = LocalDate.of(1978, 1, 1);
    private static final LocalDate BLOCK_END = LocalDate.of(1985, 12, 31);
    private static final String EVENT_TYPE = "policy.mass-tort-profile.updated";

    @Autowired
    private MassTortProfileService profileService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        for (String table : List.of(
                "outbox_event", "policy_mass_tort_profile", "policy_installment",
                "policy_billing_plan", "policy_limit_erosion", "policy_limit_state",
                "policy_limit", "policy_coverage", "policy_endorsement", "policy_insured",
                "policy_location", "policy_cancellation", "policy_reinstatement",
                "policy_version", "policy_command_idempotency", "policy_idempotency",
                "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createsTheProfileAndWritesTheUpdatedEvent() {
        Policy policy = createPolicy("POL-MT-0001");

        MassTortProfileResult result = profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("asbestos", "exposure", "all_sums",
                        "  Allocate by policy year  ", BLOCK_START, BLOCK_END, "draft"));

        assertThat(result.created()).isTrue();
        PolicyMassTortProfile profile = result.profile();
        assertThat(profile.id()).isNotNull();
        assertThat(profile.tenantId()).isEqualTo(TENANT);
        assertThat(profile.policyId()).isEqualTo(policy.id());
        assertThat(profile.tortType()).isEqualTo("ASBESTOS");
        assertThat(profile.triggerType()).isEqualTo("EXPOSURE");
        assertThat(profile.allocationMethod()).isEqualTo("ALL_SUMS");
        assertThat(profile.allocationNotes()).isEqualTo("Allocate by policy year");
        assertThat(profile.coverageBlockStart()).isEqualTo(BLOCK_START);
        assertThat(profile.coverageBlockEnd()).isEqualTo(BLOCK_END);
        assertThat(profile.status()).isEqualTo("DRAFT");
        assertThat(profile.updatedAt()).isNotNull();
        assertThat(profileService.get(TENANT, policy.id())).isEqualTo(profile);
        assertThat(policyService.get(TENANT, policy.id()).version())
                .isEqualTo(policy.version() + 1);

        Map<String, Object> event = event(EVENT_TYPE, policy.id());
        assertThat(event.get("aggregate_type")).isEqualTo("Policy");
        assertThat(event.get("aggregate_version")).isEqualTo(2L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-MT-0001\"")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"tortType\":\"ASBESTOS\"")
                .contains("\"triggerType\":\"EXPOSURE\"")
                .contains("\"allocationMethod\":\"ALL_SUMS\"")
                .contains("\"coverageBlockStart\":\"1978-01-01\"")
                .contains("\"coverageBlockEnd\":\"1985-12-31\"");
        assertThat((String) event.get("headers")).contains("\"book\":\"DIRECT\"");
    }

    @Test
    void updatesTheExistingProfileInPlaceAndKeepsADistinctEventVersion() {
        Policy policy = createPolicy("POL-MT-0002");
        MassTortProfileResult created = profileService.upsert(TENANT, policy.id(),
                draft("TALC", "INJURY", "EQUAL_SHARES"));

        MassTortProfileResult updated = profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("TALC", "MANIFESTATION", "CUSTOM_MATRIX",
                        "Matrix by year and layer", BLOCK_START, BLOCK_END, "COMPLETE"));

        assertThat(created.created()).isTrue();
        assertThat(updated.created()).isFalse();
        assertThat(updated.profile().id()).isEqualTo(created.profile().id());
        assertThat(updated.profile().status()).isEqualTo("COMPLETE");
        assertThat(updated.profile().triggerType()).isEqualTo("MANIFESTATION");
        assertThat(updated.profile().allocationMethod()).isEqualTo("CUSTOM_MATRIX");
        assertThat(updated.profile().allocationNotes()).isEqualTo("Matrix by year and layer");
        assertThat(updated.profile().coverageBlockStart()).isEqualTo(BLOCK_START);
        assertThat(updated.profile().coverageBlockEnd()).isEqualTo(BLOCK_END);
        assertThat(updated.profile().updatedAt())
                .isAfterOrEqualTo(created.profile().updatedAt());
        assertThat(profileService.get(TENANT, policy.id())).isEqualTo(updated.profile());
        assertThat(count("policy_mass_tort_profile")).isEqualTo(1);
        assertThat(policyService.get(TENANT, policy.id()).version())
                .isEqualTo(policy.version() + 2);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 ORDER BY event_sequence
                """, policy.id(), EVENT_TYPE);
        assertThat(events).hasSize(2);
        assertThat(events).extracting(row -> row.get("aggregate_version"))
                .containsExactly(2L, 3L);
        assertThat((String) events.get(1).get("payload"))
                .contains("\"status\":\"COMPLETE\"")
                .contains("\"triggerType\":\"MANIFESTATION\"")
                .contains("\"allocationMethod\":\"CUSTOM_MATRIX\"");
    }

    @Test
    void getsTheProfileAndReturns404WhenItIsMissing() {
        Policy policy = createPolicy("POL-MT-0003");
        Policy otherPolicy = createPolicy("POL-MT-0004");

        assertNotFound(() -> profileService.get(TENANT, policy.id()),
                "MASS_TORT_PROFILE_NOT_FOUND");
        assertNotFound(() -> profileService.get(TENANT,
                UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa")), "POLICY_NOT_FOUND");

        PolicyMassTortProfile created = profileService.upsert(TENANT, policy.id(),
                complete("Complete allocation notes", BLOCK_START, BLOCK_END)).profile();

        assertThat(profileService.get(TENANT, policy.id())).isEqualTo(created);
        assertNotFound(() -> profileService.get(TENANT, otherPolicy.id()),
                "MASS_TORT_PROFILE_NOT_FOUND");
    }

    @Test
    void rejectsInvalidVocabularyNotesAndDateOrderWithoutWriting() {
        Policy policy = createPolicy("POL-MT-0005");
        int versionBefore = policy.version();

        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("MOLD", "EXPOSURE", "ALL_SUMS", null,
                        null, null, "DRAFT")), "INVALID_TORT_TYPE", "tortType");
        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "OCCURRENCE", "ALL_SUMS", null,
                        null, null, "DRAFT")), "INVALID_TRIGGER_TYPE", "triggerType");
        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "PER_OCCURRENCE", null,
                        null, null, "DRAFT")), "INVALID_ALLOCATION_METHOD", "allocationMethod");
        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS", null,
                        null, null, "ACTIVE")),
                "INVALID_MASS_TORT_PROFILE_STATUS", "status");
        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS",
                        "n".repeat(2001), null, null, "DRAFT")),
                "INVALID_ALLOCATION_NOTES", "allocationNotes");
        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS", "notes",
                        BLOCK_END, BLOCK_START, "DRAFT")),
                "INVALID_COVERAGE_BLOCK", "coverageBlockEnd");

        assertThat(count("policy_mass_tort_profile")).isZero();
        assertThat(countEvents(EVENT_TYPE)).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    @Test
    void completeRequiresNotesAndBothCoverageBlockDates() {
        Policy policy = createPolicy("POL-MT-0006");

        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS", null,
                        BLOCK_START, BLOCK_END, "COMPLETE")),
                "INCOMPLETE_MASS_TORT_PROFILE", "allocationNotes");
        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS", "notes",
                        null, BLOCK_END, "COMPLETE")),
                "INCOMPLETE_MASS_TORT_PROFILE", "coverageBlockStart");
        assertBadRequest(() -> profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS", "notes",
                        BLOCK_START, null, "COMPLETE")),
                "INCOMPLETE_MASS_TORT_PROFILE", "coverageBlockEnd");

        assertThat(count("policy_mass_tort_profile")).isZero();
        assertThat(countEvents(EVENT_TYPE)).isZero();

        PolicyMassTortProfile complete = profileService.upsert(TENANT, policy.id(),
                new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS",
                        "n".repeat(2000), BLOCK_START, BLOCK_END, "COMPLETE")).profile();
        assertThat(complete.status()).isEqualTo("COMPLETE");
        assertThat(complete.allocationNotes()).hasSize(2000);
    }

    @Test
    void enforcesTheTenantWallOnReadsAndWrites() {
        Policy policy = createPolicy("POL-MT-0007");
        profileService.upsert(TENANT, policy.id(),
                complete("Tenant wall", BLOCK_START, BLOCK_END));
        int versionBefore = policyService.get(TENANT, policy.id()).version();

        assertNotFound(() -> profileService.get(OTHER_TENANT, policy.id()), "POLICY_NOT_FOUND");
        assertNotFound(() -> profileService.upsert(OTHER_TENANT, policy.id(),
                complete("Foreign attempt", BLOCK_START, BLOCK_END)), "POLICY_NOT_FOUND");

        assertThat(count("policy_mass_tort_profile")).isEqualTo(1);
        assertThat(profileService.get(TENANT, policy.id()).allocationNotes())
                .isEqualTo("Tenant wall");
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
        assertThat(countEvents(EVENT_TYPE)).isEqualTo(1);
    }

    @Test
    void createRollbackLeavesNoProfilePolicyChangeOrEvent() {
        Policy policy = createPolicy("POL-MT-0008");
        int versionBefore = policy.version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            profileService.upsert(TENANT, policy.id(),
                    complete("Rolled back", BLOCK_START, BLOCK_END));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("policy_mass_tort_profile")).isZero();
        assertThat(countEvents(EVENT_TYPE)).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    @Test
    void updateRollbackRestoresThePreviousProfileAndWritesNoEvent() {
        Policy policy = createPolicy("POL-MT-0009");
        PolicyMassTortProfile original = profileService.upsert(TENANT, policy.id(),
                complete("Original notes", BLOCK_START, BLOCK_END)).profile();
        int versionBefore = policyService.get(TENANT, policy.id()).version();
        int eventsBefore = countEvents(EVENT_TYPE);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            profileService.upsert(TENANT, policy.id(),
                    new PutMassTortProfileRequest("PFAS", "REVISIONIST", "STACKED_LIMITS",
                            "Replacement notes", BLOCK_START, BLOCK_END, "DRAFT"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(profileService.get(TENANT, policy.id())).isEqualTo(original);
        assertThat(count("policy_mass_tort_profile")).isEqualTo(1);
        assertThat(countEvents(EVENT_TYPE)).isEqualTo(eventsBefore);
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    @Test
    void storedEventReconstructsIntoADirectEnvelope() {
        Policy policy = createPolicy("POL-MT-0010");
        profileService.upsert(TENANT, policy.id(),
                complete("Envelope check", BLOCK_START, BLOCK_END));

        UUID eventId = jdbc.queryForObject("""
                SELECT id FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, UUID.class, policy.id(), EVENT_TYPE);
        OutboxEvent stored = outboxRepository.findById(eventId).orElseThrow();
        EventEnvelope envelope = stored.toEnvelope();

        assertThat(envelope.eventType()).isEqualTo(EVENT_TYPE);
        assertThat(envelope.book()).isEqualTo(Book.DIRECT);
        assertThat(envelope.aggregateType()).isEqualTo("Policy");
        assertThat(envelope.aggregateId()).isEqualTo(policy.id());
        assertThat(envelope.aggregateVersion()).isEqualTo(2L);
        assertThat(envelope.data()).containsEntry("policyNumber", "POL-MT-0010")
                .containsEntry("status", "COMPLETE")
                .containsEntry("tortType", "ASBESTOS")
                .containsEntry("coverageBlockStart", "1978-01-01");
    }

    private Policy createPolicy(String number) {
        return policyService.create(new CreatePolicyRequest(TENANT, number, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, new BigDecimal("5000000.00"), "USD",
                new BigDecimal("10000000.00"), "USD"));
    }

    private static PutMassTortProfileRequest draft(String tortType, String triggerType,
                                                   String allocationMethod) {
        return new PutMassTortProfileRequest(tortType, triggerType, allocationMethod, null,
                null, null, "DRAFT");
    }

    private static PutMassTortProfileRequest complete(String notes, LocalDate start,
                                                      LocalDate end) {
        return new PutMassTortProfileRequest("ASBESTOS", "EXPOSURE", "ALL_SUMS", notes,
                start, end, "COMPLETE");
    }

    private Map<String, Object> event(String eventType, UUID policyId) {
        return jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_version, status, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, policyId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class, eventType);
    }

    private static void assertBadRequest(ThrowingCall call, String code, String field) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.code()).isEqualTo(code);
            assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                    .contains(field);
        });
    }

    private static void assertNotFound(ThrowingCall call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(api.code()).isEqualTo(code);
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
