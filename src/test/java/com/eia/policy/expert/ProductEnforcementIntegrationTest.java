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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end active-product enforcement for new policy and submission creates.
 *
 * <p>An unknown code is {@code 422 UNKNOWN_PRODUCT}, a RETIRED code is
 * {@code 422 PRODUCT_NOT_ACTIVE}, and both identify {@code productCode} with
 * machine-readable meta. An ACTIVE code creates normally. Retiring a product
 * never invalidates accepted business: an existing policy stays readable and
 * idempotently replayable, an existing submission still transitions, and a
 * submission accepted before retirement still converts using its own product.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductEnforcementIntegrationTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = CATALOG_TENANT;
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String table : List.of(
                "outbox_event", "policy_limit_erosion", "policy_limit_state", "policy_limit",
                "policy_coverage", "policy_version", "policy_command_idempotency",
                "policy_idempotency", "policy", "underwriting_submission",
                "underwriting_submission_number_seq")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void policyCreateRejectsAnUnknownProduct() throws Exception {
        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody("POL-ENF-UNKNOWN", "NOPE")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("UNKNOWN_PRODUCT"))
                .andExpect(jsonPath("$.category").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.errors[0].field").value("productCode"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PRODUCT"))
                .andExpect(jsonPath("$.meta.productCode").value("NOPE"));

        assertThat(count("policy")).isZero();
    }

    @Test
    void policyCreateRejectsARetiredProduct() throws Exception {
        retireProduct(TENANT, CATALOG_PRODUCT_CODE);

        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody("POL-ENF-RETIRED", CATALOG_PRODUCT_CODE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.category").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.errors[0].field").value("productCode"))
                .andExpect(jsonPath("$.errors[0].code").value("PRODUCT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.meta.productCode").value(CATALOG_PRODUCT_CODE))
                .andExpect(jsonPath("$.meta.productStatus").value("RETIRED"));

        assertThat(count("policy")).isZero();
    }

    @Test
    void policyCreateAcceptsAnActiveProduct() throws Exception {
        mvc.perform(post("/internal/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody("POL-ENF-ACTIVE", " cgl ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productCode").value("cgl"));

        assertThat(count("policy")).isEqualTo(1);
    }

    @Test
    void submissionCreateRejectsAnUnknownProductWithoutAllocatingANumber() throws Exception {
        mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionBody("NOPE")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNKNOWN_PRODUCT"))
                .andExpect(jsonPath("$.errors[0].field").value("productCode"))
                .andExpect(jsonPath("$.meta.productCode").value("NOPE"));

        assertThat(count("underwriting_submission")).isZero();
        assertThat(count("underwriting_submission_number_seq")).isZero();

        // The rejected create never consumed a number: the first accepted
        // submission still starts the tenant sequence at one.
        JsonNode created = createSubmission(CATALOG_SUBMISSION_PRODUCT_CODE);
        assertThat(created.get("submissionNumber").asText()).endsWith("-0000001");
    }

    @Test
    void submissionCreateRejectsARetiredProduct() throws Exception {
        retireProduct(TENANT, CATALOG_SUBMISSION_PRODUCT_CODE);

        mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionBody(CATALOG_SUBMISSION_PRODUCT_CODE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_ACTIVE"))
                .andExpect(jsonPath("$.errors[0].field").value("productCode"))
                .andExpect(jsonPath("$.meta.productCode")
                        .value(CATALOG_SUBMISSION_PRODUCT_CODE))
                .andExpect(jsonPath("$.meta.productStatus").value("RETIRED"));

        assertThat(count("underwriting_submission")).isZero();
        assertThat(count("underwriting_submission_number_seq")).isZero();
    }

    @Test
    void submissionCreateAcceptsAnActiveProduct() throws Exception {
        JsonNode created = createSubmission(CATALOG_SUBMISSION_PRODUCT_CODE);

        assertThat(created.get("productCode").asText())
                .isEqualTo(CATALOG_SUBMISSION_PRODUCT_CODE);
        assertThat(created.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(count("underwriting_submission")).isEqualTo(1);
    }

    @Test
    void conversionUsesTheSubmissionProductAfterThatProductIsRetired() throws Exception {
        JsonNode submission = createSubmission(CATALOG_SUBMISSION_PRODUCT_CODE);
        UUID submissionId = UUID.fromString(submission.get("submissionId").asText());
        quote(submissionId);

        retireProduct(TENANT, CATALOG_SUBMISSION_PRODUCT_CODE);

        mvc.perform(post("/api/v1/policies/submissions/{id}/convert", submissionId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conversionBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.productCode").value(CATALOG_SUBMISSION_PRODUCT_CODE))
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()));

        mvc.perform(get("/api/v1/policies/submissions/{id}", submissionId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andExpect(jsonPath("$.policyId").isNotEmpty());
    }

    @Test
    void existingPolicyAndSubmissionSurviveTheirProductBeingRetired() throws Exception {
        MvcResult created = mvc.perform(post("/internal/v1/policies")
                        .header("Idempotency-Key", "retire-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody("POL-ENF-EXISTING", CATALOG_PRODUCT_CODE)))
                .andExpect(status().isCreated())
                .andReturn();
        String policyId = body(created).get("id").asText();

        JsonNode submission = createSubmission(CATALOG_SUBMISSION_PRODUCT_CODE);
        UUID submissionId = UUID.fromString(submission.get("submissionId").asText());

        retireProduct(TENANT, CATALOG_PRODUCT_CODE);
        retireProduct(TENANT, CATALOG_SUBMISSION_PRODUCT_CODE);

        mvc.perform(get("/internal/v1/policies/{id}", policyId)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value(CATALOG_PRODUCT_CODE));

        quote(submissionId);

        // A create replay is not new business: it returns the accepted policy
        // without re-checking the now-retired catalog product.
        mvc.perform(post("/internal/v1/policies")
                        .header("Idempotency-Key", "retire-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody("POL-ENF-EXISTING", CATALOG_PRODUCT_CODE)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(policyId));
    }

    private JsonNode createSubmission(String productCode) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/policies/submissions")
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionBody(productCode)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    private void quote(UUID submissionId) throws Exception {
        mvc.perform(patch("/api/v1/policies/submissions/{id}/status", submissionId)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"QUOTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"));
    }

    private void retireProduct(UUID tenantId, String productCode) throws Exception {
        UUID productId = jdbc.queryForObject("""
                SELECT id FROM product WHERE tenant_id = ? AND product_code = ?
                """, UUID.class, tenantId, productCode);
        mvc.perform(patch("/api/v1/products/{id}/status", productId)
                        .param("tenantId", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String policyBody(String policyNumber, String productCode) {
        return """
                {"tenantId":"%s","policyNumber":"%s","productCode":"%s",
                 "insuredPartyId":"%s","effectiveDate":"2026-01-01",
                 "expirationDate":"2027-01-01","premiumAmount":120000.00,
                 "premiumCurrency":"USD","limitAmount":5000000.00,
                 "limitCurrency":"USD"}
                """.formatted(TENANT, policyNumber, productCode, PARTY);
    }

    private static String submissionBody(String productCode) {
        return """
                {"applicantPartyId":"%s","productCode":"%s",
                 "requestedEffectiveDate":"2026-11-01","requestedLimitAmount":2500000.00,
                 "requestedLimitCurrency":"USD","tortCodes":["ASBESTOS"],
                 "notes":"Active-product enforcement"}
                """.formatted(PARTY, productCode);
    }

    private static String conversionBody() {
        return "{\"premiumAmount\":125000.00,\"premiumCurrency\":\"USD\"}";
    }
}
