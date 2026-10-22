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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end underwriting submission flow on H2: per-tenant numbering,
 * create/list/get, the one-way status graph, the tenant wall, validation, and
 * the transactional outbox events. The rollback test proves a failed create
 * leaves neither the row nor the event behind while the number sequence keeps
 * its normal gap semantics.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnderwritingSubmissionIntegrationTest {
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
    private UnderwritingSubmissionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        for (String table : List.of("outbox_event", "underwriting_submission",
                "underwriting_submission_number_seq")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createsListsGetsAndTransitionsASubmission() throws Exception {
        JsonNode created = create(TENANT, body());
        UUID submissionId = UUID.fromString(created.get("submissionId").asText());

        assertThat(created.get("submissionNumber").asText())
                .matches("SUB-\\d{4}-0000001");
        assertThat(created.get("applicantPartyId").asText()).isEqualTo(APPLICANT.toString());
        assertThat(created.get("productCode").asText()).isEqualTo("CGL_PRODUCTS");
        assertThat(created.get("requestedEffectiveDate").asText()).isEqualTo("2026-11-01");
        assertThat(created.get("requestedLimitAmount").decimalValue())
                .isEqualByComparingTo("2500000.0000");
        assertThat(created.get("requestedLimitCurrency").asText()).isEqualTo("USD");
        assertThat(created.get("tortCodes").size()).isEqualTo(2);
        assertThat(created.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(created.get("notes").asText()).isEqualTo("Large manufacturing risk");
        assertThat(created.get("version").asInt()).isZero();

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()))
                .andExpect(jsonPath("$.submissionNumber")
                        .value(created.get("submissionNumber").asText()))
                .andExpect(jsonPath("$.version").value(0));

        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].submissionId").value(submissionId.toString()));

        String number = created.get("submissionNumber").asText();
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .param("q", number.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .param("q", "PRODUCTS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .param("q", "no-such-submission"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .param("status", "received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .param("status", "BOUND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .param("status", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBMISSION_STATUS"));

        transition(submissionId, "REFERRED", "Large limit needs review")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.version").value(1));

        transition(submissionId, "QUOTED", null)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.version").value(2));

        transition(submissionId, "BOUND", null)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andExpect(jsonPath("$.version").value(3));

        transition(submissionId, "DECLINED", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBMISSION_TRANSITION"))
                .andExpect(jsonPath("$.meta.currentStatus").value("BOUND"))
                .andExpect(jsonPath("$.meta.targetStatus").value("DECLINED"));

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andExpect(jsonPath("$.version").value(3));

        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .param("status", "BOUND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void numbersSubmissionsPerTenantAndEnforcesTheTenantWall() throws Exception {
        JsonNode first = create(TENANT, body());
        JsonNode second = create(TENANT, body());
        JsonNode other = create(OTHER_TENANT, body());

        assertThat(first.get("submissionNumber").asText()).endsWith("-0000001");
        assertThat(second.get("submissionNumber").asText()).endsWith("-0000002");
        assertThat(other.get("submissionNumber").asText()).endsWith("-0000001");

        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
        mvc.perform(get("/api/v1/policies/submissions")
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].submissionId")
                        .value(other.get("submissionId").asText()));

        UUID firstId = UUID.fromString(first.get("submissionId").asText());
        mvc.perform(get("/api/v1/policies/submissions/{id}", firstId)
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));
        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", firstId)
                        .param("tenantId", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REFERRED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));
    }

    @Test
    void writesCreatedAndStatusChangedEventsInTheSameTransaction() throws Exception {
        JsonNode created = create(TENANT, body());
        UUID submissionId = UUID.fromString(created.get("submissionId").asText());

        List<Map<String, Object>> createdEvents = jdbc.queryForList("""
                SELECT aggregate_type, aggregate_version, tenant_id, partition_key, payload
                  FROM outbox_event
                 WHERE event_type = 'policy.submission.created'
                """);
        assertThat(createdEvents).hasSize(1);
        Map<String, Object> createdEvent = createdEvents.get(0);
        assertThat(createdEvent.get("aggregate_type")).isEqualTo("UnderwritingSubmission");
        assertThat(createdEvent.get("aggregate_version")).isEqualTo(1L);
        assertThat(createdEvent.get("tenant_id")).isEqualTo(TENANT);
        assertThat(createdEvent.get("partition_key")).isEqualTo(submissionId.toString());
        assertThat((String) createdEvent.get("payload"))
                .contains("\"submissionId\":\"" + submissionId + "\"")
                .contains("\"submissionNumber\":\""
                        + created.get("submissionNumber").asText() + "\"")
                .contains("\"applicantPartyId\":\"" + APPLICANT + "\"")
                .contains("\"productCode\":\"CGL_PRODUCTS\"")
                .contains("\"requestedLimitAmount\":\"2500000.0000\"")
                .contains("\"requestedLimitCurrency\":\"USD\"")
                .contains("\"tortCodes\":[\"ASBESTOS\",\"PFAS\"]")
                .contains("\"status\":\"RECEIVED\"")
                .contains("\"notes\":\"Large manufacturing risk\"");

        transition(submissionId, "REFERRED", "Large limit needs review")
                .andExpect(status().isOk());
        transition(submissionId, "QUOTED", null)
                .andExpect(status().isOk());

        List<Map<String, Object>> changed = jdbc.queryForList("""
                SELECT aggregate_version, payload
                  FROM outbox_event
                 WHERE event_type = 'policy.submission.status-changed'
                 ORDER BY event_sequence
                """);
        assertThat(changed).hasSize(2);
        assertThat(changed.get(0).get("aggregate_version")).isEqualTo(2L);
        assertThat((String) changed.get(0).get("payload"))
                .contains("\"submissionId\":\"" + submissionId + "\"")
                .contains("\"previousStatus\":\"RECEIVED\"")
                .contains("\"status\":\"REFERRED\"")
                .contains("\"version\":1")
                .contains("\"reason\":\"Large limit needs review\"");
        assertThat(changed.get(1).get("aggregate_version")).isEqualTo(3L);
        assertThat((String) changed.get(1).get("payload"))
                .contains("\"previousStatus\":\"REFERRED\"")
                .contains("\"status\":\"QUOTED\"")
                .contains("\"version\":2")
                .contains("\"reason\":null");

        // A rejected transition writes neither a row change nor an event.
        transition(submissionId, "RECEIVED", null)
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = "
                        + "'policy.submission.status-changed'", Integer.class)).isEqualTo(2);
    }

    @Test
    void validatesTheCreateBodyWithoutWritingAnything() throws Exception {
        String tooLongProduct = "P".repeat(65);
        String tooManyTortCodes = "\"" + String.join("\",\"",
                java.util.Collections.nCopies(21, "CODE")) + "\"";
        String tooLongTortCode = "T".repeat(33);
        String tooLongNotes = "N".repeat(2001);

        List<String> invalidBodies = List.of(
                """
                {"applicantPartyId":"%s","productCode":"%s","requestedEffectiveDate":"2026-11-01",
                 "requestedLimitAmount":1000,"requestedLimitCurrency":"USD"}
                """.formatted(APPLICANT, tooLongProduct),
                """
                {"applicantPartyId":"%s","productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                 "requestedLimitAmount":1000,"requestedLimitCurrency":"US"}
                """.formatted(APPLICANT),
                """
                {"applicantPartyId":"%s","productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                 "requestedLimitAmount":-1,"requestedLimitCurrency":"USD"}
                """.formatted(APPLICANT),
                """
                {"applicantPartyId":"%s","productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                 "requestedLimitAmount":1000,"requestedLimitCurrency":"USD",
                 "tortCodes":[%s]}
                """.formatted(APPLICANT, tooManyTortCodes),
                """
                {"applicantPartyId":"%s","productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                 "requestedLimitAmount":1000,"requestedLimitCurrency":"USD",
                 "tortCodes":["%s"]}
                """.formatted(APPLICANT, tooLongTortCode),
                """
                {"applicantPartyId":"%s","productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                 "requestedLimitAmount":1000,"requestedLimitCurrency":"USD",
                 "notes":"%s"}
                """.formatted(APPLICANT, tooLongNotes),
                """
                {"productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                 "requestedLimitAmount":1000,"requestedLimitCurrency":"USD"}
                """);

        for (String body : invalidBodies) {
            MvcResult result = mvc.perform(post("/api/v1/policies/submissions")
                            .param("tenantId", TENANT.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            JsonNode problem = objectMapper.readTree(
                    result.getResponse().getContentAsString());
            assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }

        assertThat(count("underwriting_submission")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(count("underwriting_submission_number_seq")).isZero();
    }

    @Test
    void acceptsEmptyTortCodesAndNoNotes() throws Exception {
        JsonNode created = create(TENANT, """
                {"applicantPartyId":"%s","productCode":"CGL","requestedEffectiveDate":"2026-11-01",
                 "requestedLimitAmount":0,"requestedLimitCurrency":"usd","tortCodes":[]}
                """.formatted(APPLICANT));

        assertThat(created.get("tortCodes").isEmpty()).isTrue();
        assertThat(created.get("notes").isNull()).isTrue();
        assertThat(created.get("requestedLimitCurrency").asText()).isEqualTo("USD");
        assertThat(created.get("requestedLimitAmount").decimalValue())
                .isEqualByComparingTo("0.0000");
    }

    @Test
    void aRolledBackCreateLeavesNoRowAndNoEventButKeepsTheSequenceGap() throws Exception {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.create(TENANT, new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL",
                    EFFECTIVE, new BigDecimal("1000000"), "USD", List.of("PFAS"), null));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("underwriting_submission")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT next_value FROM underwriting_submission_number_seq WHERE tenant_id = ?
                """, Long.class, TENANT)).isEqualTo(1L);

        JsonNode next = create(TENANT, body());
        assertThat(next.get("submissionNumber").asText()).endsWith("-0000002");
    }

    private JsonNode create(UUID tenantId, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions transition(
            UUID submissionId, String status, String reason) throws Exception {
        String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return mvc.perform(patch("/api/v1/policies/submissions/{id}/status", submissionId)
                .param("tenantId", TENANT.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"" + reasonJson + "}"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String body() {
        return """
                {"applicantPartyId":"%s","productCode":"CGL_PRODUCTS",
                 "requestedEffectiveDate":"2026-11-01","requestedLimitAmount":2500000.00,
                 "requestedLimitCurrency":"USD","tortCodes":["ASBESTOS","PFAS"],
                 "notes":"Large manufacturing risk"}
                """.formatted(APPLICANT);
    }
}
