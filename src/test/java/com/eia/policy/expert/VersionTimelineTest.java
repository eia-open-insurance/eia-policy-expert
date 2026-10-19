package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionTimelineTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID APPROVER = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID APPROVAL = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);

    @Test
    void acceptsADateInsideTheTerm() {
        assertThat(VersionTimeline.resolve(LocalDate.of(2026, 6, 1), policy(), version(1),
                null, null, null)).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void rejectsARegressionWithoutJustification() {
        assertThatThrownBy(() -> VersionTimeline.resolve(LocalDate.of(2026, 2, 1), policy(),
                version(3), null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("VERSION_REGRESSION");
                });
    }

    @Test
    void clampsAnApprovedRegressionToTheCurrentVersionStart() {
        assertThat(VersionTimeline.resolve(LocalDate.of(2026, 2, 1), policy(), version(3),
                "Retroactive correction", APPROVER, APPROVAL))
                .isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void requiresAReasonForABackdatedChange() {
        assertThatThrownBy(() -> VersionTimeline.resolve(LocalDate.of(2025, 12, 1), policy(),
                version(1), null, APPROVER, APPROVAL))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("VERSION_BACKDATE_REASON_REQUIRED"));
    }

    @Test
    void requiresAnApprovalForABackdatedChange() {
        assertThatThrownBy(() -> VersionTimeline.resolve(LocalDate.of(2025, 12, 1), policy(),
                version(1), "Retroactive binder", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(api.code()).isEqualTo("APPROVAL_REQUIRED");
                });
    }

    @Test
    void clampsAnApprovedBackdateToTheCurrentVersionStart() {
        assertThat(VersionTimeline.resolve(LocalDate.of(2025, 12, 1), policy(), version(3),
                "Retroactive binder", APPROVER, APPROVAL)).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void clampsAnApprovedBackdateToPolicyInceptionWithoutACurrentVersion() {
        assertThat(VersionTimeline.resolve(LocalDate.of(2025, 12, 1), policy(), null,
                "Retroactive binder", APPROVER, APPROVAL)).isEqualTo(EFFECTIVE);
    }

    @Test
    void rejectsADateAtOrAfterExpiration() {
        assertThatThrownBy(() -> VersionTimeline.resolve(EXPIRATION, policy(), version(1),
                null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("VERSION_OUTSIDE_POLICY_TERM"));
    }

    @Test
    void validatesExplicitWindows() {
        assertThatCode(() -> VersionTimeline.validateExplicitRange(EFFECTIVE,
                LocalDate.of(2026, 7, 1), EXPIRATION)).doesNotThrowAnyException();
        assertThatThrownBy(() -> VersionTimeline.validateExplicitRange(EFFECTIVE, EFFECTIVE,
                EXPIRATION))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_VERSION_RANGE"));
        assertThatThrownBy(() -> VersionTimeline.validateExplicitRange(EFFECTIVE,
                EXPIRATION.plusDays(1), EXPIRATION))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("VERSION_OUTSIDE_POLICY_TERM"));
    }

    @Test
    void rejectsOverlapWithAnotherActiveVersion() {
        List<VersionTimeline.Window> windows = List.of(
                new VersionTimeline.Window(UUID.randomUUID(), EFFECTIVE, LocalDate.of(2026, 4, 1),
                        "SUPERSEDED"),
                new VersionTimeline.Window(UUID.randomUUID(), LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 7, 1), "ISSUED"),
                new VersionTimeline.Window(UUID.randomUUID(), LocalDate.of(2026, 7, 1), null,
                        "ISSUED"));

        assertThatThrownBy(() -> VersionTimeline.validateNoOverlap(LocalDate.of(2026, 6, 1), null,
                EXPIRATION, VERSION_ID, windows))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("VERSION_RANGE_OVERLAP");
                });
    }

    @Test
    void ignoresTheSupersededVersionAndClosedWindows() {
        List<VersionTimeline.Window> windows = List.of(
                new VersionTimeline.Window(VERSION_ID, EFFECTIVE, null, "ISSUED"));

        assertThatCode(() -> VersionTimeline.validateNoOverlap(LocalDate.of(2026, 6, 1), null,
                EXPIRATION, VERSION_ID, windows)).doesNotThrowAnyException();
    }

    private static Policy policy() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "ISSUED",
                EFFECTIVE, EXPIRATION, new BigDecimal("125000.0000"), "USD",
                new BigDecimal("2000000.0000"), "USD", 3, now, now);
    }

    private static PolicyVersion version(int number) {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new PolicyVersion(VERSION_ID, TENANT, POLICY_ID, number,
                "POL-2026-0000001-0" + number, "ENDORSEMENT", "ISSUED",
                LocalDate.of(2026, 4, 1), null, now, null, null, null, null, null,
                "Endorsement", null, "hash", now);
    }
}
