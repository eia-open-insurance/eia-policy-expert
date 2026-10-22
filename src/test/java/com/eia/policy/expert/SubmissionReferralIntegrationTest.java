package com.eia.policy.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end structured referral flow on H2: opening and updating a referral,
 * approving and declining it, the lifecycle status interaction, validation,
 * the tenant wall, and the transactional outbox events.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubmissionReferralIntegrationTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID APPLICANT =
            UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 11, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String table : List.of("outbox_event", "underwriting_submission",
                "underwriting_submission_number_seq")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createsAndUpdatesAStructuredReferralAndEmitsTheReferredEvent() throws Exception {
        JsonNode created = createSubmission(TENANT);
        UUID submissionId = UUID.fromString(created.get("submissionId").asText());
        assertThat(created.get("referralReason").isNull()).isTrue();
        assertThat(created.get("referralDecision").isNull()).isTrue();

        refer(submissionId, TENANT, """
                {"reason":"  Aggregate limit above authority  ",
                 "authority":"  Chief Underwriting Officer  ",
                 "referredTo":"  Senior UW - Casualty  ",
                 "notes":"  Needs reinsurance sign-off  "}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.referralReason")
                        .value("Aggregate limit above authority"))
                .andExpect(jsonPath("$.referralAuthority")
                        .value("Chief Underwriting Officer"))
                .andExpect(jsonPath("$.referredTo").value("Senior UW - Casualty"))
                .andExpect(jsonPath("$.referralNotes").value("Needs reinsurance sign-off"))
                .andExpect(jsonPath("$.referralDecision").value("PENDING"))
                .andExpect(jsonPath("$.referralDecidedBy").isEmpty())
                .andExpect(jsonPath("$.referralDecidedAt").isEmpty())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.referralDecision").value("PENDING"))
                .andExpect(jsonPath("$.referralReason")
                        .value("Aggregate limit above authority"));

        // A referral update while REFERRED is an upsert of the details, not a
        // second lifecycle transition: the status stays REFERRED and the
        // decision restarts at PENDING.
        refer(submissionId, TENANT, """
                {"reason":"Limit above local authority","authority":"CUO",
                 "referredTo":"Reinsurance desk"}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.referralReason").value("Limit above local authority"))
                .andExpect(jsonPath("$.referralAuthority").value("CUO"))
                .andExpect(jsonPath("$.referredTo").value("Reinsurance desk"))
                .andExpect(jsonPath("$.referralNotes").isEmpty())
                .andExpect(jsonPath("$.referralDecision").value("PENDING"))
                .andExpect(jsonPath("$.version").value(2));

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT aggregate_type, aggregate_version, tenant_id, partition_key, payload
                  FROM outbox_event
                 WHERE event_type = 'policy.submission.referred'
                 ORDER BY event_sequence
                """);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("aggregate_type")).isEqualTo("UnderwritingSubmission");
        assertThat(events.get(0).get("aggregate_version")).isEqualTo(2L);
        assertThat(events.get(0).get("tenant_id")).isEqualTo(TENANT);
        assertThat(events.get(0).get("partition_key")).isEqualTo(submissionId.toString());
        assertThat((String) events.get(0).get("payload"))
                .contains("\"submissionId\":\"" + submissionId + "\"")
                .contains("\"submissionNumber\":\"" + created.get("submissionNumber").asText()
                        + "\"")
                .contains("\"previousStatus\":\"RECEIVED\"")
                .contains("\"status\":\"REFERRED\"")
                .contains("\"referralReason\":\"Aggregate limit above authority\"")
                .contains("\"referralAuthority\":\"Chief Underwriting Officer\"")
                .contains("\"referredTo\":\"Senior UW - Casualty\"")
                .contains("\"referralNotes\":\"Needs reinsurance sign-off\"")
                .contains("\"referralDecision\":\"PENDING\"")
                .contains("\"version\":1");
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(3L);
        assertThat((String) events.get(1).get("payload"))
                .contains("\"previousStatus\":\"REFERRED\"")
                .contains("\"referralReason\":\"Limit above local authority\"")
                .contains("\"referralNotes\":null")
                .contains("\"version\":2");
    }

    @Test
    void approvesAPendingReferralAndKeepsTheSubmissionReferred() throws Exception {
        UUID submissionId = UUID.fromString(createSubmission(TENANT)
                .get("submissionId").asText());
        refer(submissionId, TENANT, """
                {"reason":"Aggregate limit above authority",
                 "authority":"Chief Underwriting Officer",
                 "referredTo":"Senior UW - Casualty",
                 "notes":"Needs reinsurance sign-off"}
                """).andExpect(status().isOk());

        decide(submissionId, TENANT, """
                {"decision":" approved ","decidedBy":"  Chief Underwriter  ",
                 "notes":"  Approved within authority  "}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.referralDecision").value("APPROVED"))
                .andExpect(jsonPath("$.referralDecidedBy").value("Chief Underwriter"))
                .andExpect(jsonPath("$.referralDecidedAt").isNotEmpty())
                .andExpect(jsonPath("$.referralNotes").value("Approved within authority"))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.referralDecision").value("APPROVED"))
                .andExpect(jsonPath("$.referralDecidedBy").value("Chief Underwriter"));

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT aggregate_version, payload
                  FROM outbox_event
                 WHERE event_type = 'policy.submission.referral-decided'
                """);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("aggregate_version")).isEqualTo(3L);
        assertThat((String) events.get(0).get("payload"))
                .contains("\"submissionId\":\"" + submissionId + "\"")
                .contains("\"previousStatus\":\"REFERRED\"")
                .contains("\"status\":\"REFERRED\"")
                .contains("\"decision\":\"APPROVED\"")
                .contains("\"decidedBy\":\"Chief Underwriter\"")
                .contains("\"notes\":\"Approved within authority\"")
                .contains("\"referralReason\":\"Aggregate limit above authority\"")
                .contains("\"decidedAt\":\"")
                .contains("\"version\":2");

        // A decision is single-shot: replaying it is a conflict and writes no
        // additional event.
        decide(submissionId, TENANT,
                "{\"decision\":\"DECLINED\",\"decidedBy\":\"Chief Underwriter\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERRAL_NOT_PENDING"))
                .andExpect(jsonPath("$.meta.currentStatus").value("REFERRED"))
                .andExpect(jsonPath("$.meta.referralDecision").value("APPROVED"));
        assertThat(countEvents("policy.submission.referral-decided")).isEqualTo(1);
    }

    @Test
    void decliningAPendingReferralAlsoDeclinesTheSubmission() throws Exception {
        UUID submissionId = UUID.fromString(createSubmission(TENANT)
                .get("submissionId").asText());
        refer(submissionId, TENANT, """
                {"reason":"Aggregate limit above authority","authority":"CUO",
                 "referredTo":"Senior UW","notes":"Needs reinsurance sign-off"}
                """).andExpect(status().isOk());

        decide(submissionId, TENANT, """
                {"decision":"DECLINED","decidedBy":"Chief Underwriter"}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.referralDecision").value("DECLINED"))
                .andExpect(jsonPath("$.referralDecidedBy").value("Chief Underwriter"))
                .andExpect(jsonPath("$.referralDecidedAt").isNotEmpty())
                .andExpect(jsonPath("$.referralNotes").value("Needs reinsurance sign-off"))
                .andExpect(jsonPath("$.version").value(2));

        // DECLINED is terminal for the lifecycle, so it cannot be referred
        // again or transitioned again.
        refer(submissionId, TENANT, """
                {"reason":"Retry","authority":"CUO","referredTo":"Senior UW"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_REFERRABLE"))
                .andExpect(jsonPath("$.meta.currentStatus").value("DECLINED"));
        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", submissionId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"QUOTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBMISSION_TRANSITION"));

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT payload FROM outbox_event
                 WHERE event_type = 'policy.submission.referral-decided'
                """);
        assertThat(events).hasSize(1);
        assertThat((String) events.get(0).get("payload"))
                .contains("\"previousStatus\":\"REFERRED\"")
                .contains("\"status\":\"DECLINED\"")
                .contains("\"decision\":\"DECLINED\"")
                .contains("\"notes\":\"Needs reinsurance sign-off\"");
    }

    @Test
    void aDecisionRequiresAPendingReferral() throws Exception {
        UUID submissionId = UUID.fromString(createSubmission(TENANT)
                .get("submissionId").asText());

        // RECEIVED without a referral.
        decide(submissionId, TENANT,
                "{\"decision\":\"APPROVED\",\"decidedBy\":\"Chief Underwriter\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERRAL_NOT_PENDING"))
                .andExpect(jsonPath("$.meta.currentStatus").value("RECEIVED"));

        // REFERRED through the generic status route still has no structured
        // referral, so there is nothing to decide.
        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", submissionId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REFERRED\"}"))
                .andExpect(status().isOk());
        decide(submissionId, TENANT,
                "{\"decision\":\"APPROVED\",\"decidedBy\":\"Chief Underwriter\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERRAL_NOT_PENDING"))
                .andExpect(jsonPath("$.meta.currentStatus").value("REFERRED"));

        // Opening the structured referral at this point is allowed and starts
        // the pending decision, which can then be approved.
        refer(submissionId, TENANT, """
                {"reason":"Limit","authority":"CUO","referredTo":"Senior UW"}
                """).andExpect(status().isOk());
        decide(submissionId, TENANT,
                "{\"decision\":\"APPROVED\",\"decidedBy\":\"Chief Underwriter\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.referralDecision").value("APPROVED"));

        assertThat(countEvents("policy.submission.referral-decided")).isEqualTo(1);
    }

    @Test
    void validatesReferralAndDecisionBodiesWithoutWritingAnything() throws Exception {
        UUID submissionId = UUID.fromString(createSubmission(TENANT)
                .get("submissionId").asText());

        List<String> invalidReferrals = List.of(
                """
                {"authority":"CUO","referredTo":"Senior UW"}
                """,
                """
                {"reason":"Limit","referredTo":"Senior UW"}
                """,
                """
                {"reason":"Limit","authority":"CUO"}
                """,
                """
                {"reason":"%s","authority":"CUO","referredTo":"Senior UW"}
                """.formatted("R".repeat(65)),
                """
                {"reason":"Limit","authority":"%s","referredTo":"Senior UW"}
                """.formatted("A".repeat(65)),
                """
                {"reason":"Limit","authority":"CUO","referredTo":"%s"}
                """.formatted("P".repeat(129)),
                """
                {"reason":"Limit","authority":"CUO","referredTo":"Senior UW",
                 "notes":"%s"}
                """.formatted("N".repeat(2001)));
        for (String body : invalidReferrals) {
            refer(submissionId, TENANT, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        List<String> invalidDecisions = List.of(
                """
                {"decidedBy":"Chief Underwriter"}
                """,
                """
                {"decision":"APPROVED"}
                """,
                """
                {"decision":"PENDING","decidedBy":"Chief Underwriter"}
                """,
                """
                {"decision":"MAYBE","decidedBy":"Chief Underwriter"}
                """,
                """
                {"decision":"APPROVED","decidedBy":"%s"}
                """.formatted("D".repeat(129)),
                """
                {"decision":"APPROVED","decidedBy":"Chief Underwriter","notes":"%s"}
                """.formatted("N".repeat(2001)));
        for (String body : invalidDecisions) {
            decide(submissionId, TENANT, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is("VALIDATION_ERROR"),
                            org.hamcrest.Matchers.is(
                                    "INVALID_SUBMISSION_REFERRAL_DECISION"))));
        }

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.referralDecision").isEmpty())
                .andExpect(jsonPath("$.referralReason").isEmpty())
                .andExpect(jsonPath("$.version").value(0));
        assertThat(countEvents("policy.submission.created")).isEqualTo(1);
        assertThat(countEvents("policy.submission.referred")).isZero();
        assertThat(countEvents("policy.submission.referral-decided")).isZero();
    }

    @Test
    void enforcesTheTenantWallOnReferralAndDecision() throws Exception {
        UUID submissionId = UUID.fromString(createSubmission(TENANT)
                .get("submissionId").asText());

        refer(submissionId, OTHER_TENANT, """
                {"reason":"Limit","authority":"CUO","referredTo":"Senior UW"}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));
        decide(submissionId, OTHER_TENANT,
                "{\"decision\":\"APPROVED\",\"decidedBy\":\"Chief Underwriter\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));

        // The other tenant can refer its own submission without seeing this one.
        UUID otherSubmissionId = UUID.fromString(createSubmission(OTHER_TENANT)
                .get("submissionId").asText());
        refer(otherSubmissionId, OTHER_TENANT, """
                {"reason":"Other tenant limit","authority":"Other CUO",
                 "referredTo":"Other Senior UW"}
                """).andExpect(status().isOk());

        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].submissionId").value(submissionId.toString()))
                .andExpect(jsonPath("$[0].status").value("RECEIVED"));
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", OTHER_TENANT.toString())
                        .param("status", "REFERRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].submissionId").value(otherSubmissionId.toString()))
                .andExpect(jsonPath("$[0].referralDecision").value("PENDING"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = "
                        + "'policy.submission.referred'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM underwriting_submission
                 WHERE tenant_id = ? AND referral_decision IS NOT NULL
                """, Integer.class, TENANT)).isZero();
    }

    @Test
    void rejectsAReferralOnceTheSubmissionHasMovedPastIntake() throws Exception {
        JsonNode created = createSubmission(TENANT);
        UUID submissionId = UUID.fromString(created.get("submissionId").asText());

        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", submissionId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"QUOTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        refer(submissionId, TENANT, """
                {"reason":"Limit","authority":"CUO","referredTo":"Senior UW"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_REFERRABLE"))
                .andExpect(jsonPath("$.meta.submissionId").value(submissionId.toString()))
                .andExpect(jsonPath("$.meta.currentStatus").value("QUOTED"));

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.referralDecision").isEmpty())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(countEvents("policy.submission.referred")).isZero();
    }

    @Test
    void requiresEveryReferralMemberAtTheHttpBoundary() throws Exception {
        UUID submissionId = UUID.fromString(createSubmission(TENANT)
                .get("submissionId").asText());

        MvcResult result = mvc.perform(
                        post("/api/v1/policies/submissions/{id}/referral", submissionId)
                                .param("tenantId", TENANT.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn();
        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.get("errors")).isNotEmpty();
        assertThat(problem.get("errors").findValuesAsText("field"))
                .contains("reason", "authority", "referredTo");

        mvc.perform(post("/api/v1/policies/submissions/{id}/referral/decision", submissionId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("decision", "decidedBy")));
    }

    private JsonNode createSubmission(UUID tenantId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicantPartyId":"%s","productCode":"CGL_PRODUCTS",
                                 "requestedEffectiveDate":"2026-11-01",
                                 "requestedLimitAmount":2500000.00,
                                 "requestedLimitCurrency":"USD",
                                 "tortCodes":["ASBESTOS","PFAS"],
                                 "notes":"Large manufacturing risk"}
                                """.formatted(APPLICANT)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions refer(UUID submissionId, UUID tenantId, String body)
            throws Exception {
        return mvc.perform(post("/api/v1/policies/submissions/{id}/referral", submissionId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions decide(UUID submissionId, UUID tenantId, String body)
            throws Exception {
        return mvc.perform(
                post("/api/v1/policies/submissions/{id}/referral/decision", submissionId)
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?",
                Integer.class, eventType);
    }
}
