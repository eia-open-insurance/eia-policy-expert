package com.eia.policy.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PolicyPartyController.class)
class PolicyPartyControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID PARTY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID INSURED_ID = UUID.fromString("cccccccc-cccc-7ccc-8ccc-cccccccccccc");
    private static final UUID LOCATION_ID = UUID.fromString("dddddddd-dddd-7ddd-8ddd-dddddddddddd");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PolicyPartyService service;

    @Test
    void listsInsureds() throws Exception {
        when(service.listInsureds(TENANT, POLICY_ID, 0, 20)).thenReturn(
                new ItemPage<>(List.of(insured()), 0, 20, 1, null));

        mvc.perform(get("/internal/v1/policies/{policyId}/insureds", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].insuredRole").value("NAMED_INSURED"))
                .andExpect(jsonPath("$.items[0].displayName").value("Acme Chemical Corporation"));
    }

    @Test
    void addsANamedInsuredWithEtag() throws Exception {
        when(service.createInsured(eq(TENANT), eq(POLICY_ID), any(CreateInsuredRequest.class),
                eq("\"0\""), eq(null)))
                .thenReturn(new InsuredResult(insured(), 1, false));

        mvc.perform(post("/internal/v1/policies/{policyId}/insureds", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(insuredRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.partyId").value(PARTY_ID.toString()));
    }

    @Test
    void listsLocations() throws Exception {
        when(service.listLocations(TENANT, POLICY_ID, 0, 20)).thenReturn(
                new ItemPage<>(List.of(location()), 0, 20, 1, null));

        mvc.perform(get("/internal/v1/policies/{policyId}/locations", POLICY_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].locationCode").value("LOC-001"))
                .andExpect(jsonPath("$.items[0].stateRegion").value("US-OH"))
                .andExpect(jsonPath("$.items[0].country").value("USA"));
    }

    @Test
    void addsALocation() throws Exception {
        when(service.createLocation(eq(TENANT), eq(POLICY_ID), any(CreateLocationRequest.class),
                eq(null), eq("key-1")))
                .thenReturn(new LocationResult(location(), 2, false));

        mvc.perform(post("/internal/v1/policies/{policyId}/locations", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locationRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.locationCode").value("LOC-001"));
    }

    @Test
    void validatesTheRegionCode() throws Exception {
        mvc.perform(post("/internal/v1/policies/{policyId}/locations", POLICY_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationCode":"LOC-001","addressLine1":"1 Main St",
                                 "city":"Akron","stateRegion":"OHIO","country":"USA",
                                 "effectiveFrom":"2026-01-01","effectiveTo":"2027-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("stateRegion"));
    }

    private static CreateInsuredRequest insuredRequest() {
        return new CreateInsuredRequest(PARTY_ID, "NAMED_INSURED", "Acme Chemical Corporation",
                "CERT-1", null, FROM, TO);
    }

    private static CreateLocationRequest locationRequest() {
        return new CreateLocationRequest("LOC-001", "1200 Industrial Way", "Suite 400", "Akron",
                "US-OH", "44311", "USA", FROM, TO);
    }

    private static PolicyInsured insured() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyInsured(INSURED_ID, TENANT, POLICY_ID, PARTY_ID, "NAMED_INSURED",
                "Acme Chemical Corporation", "CERT-1", null, FROM, TO, "ACTIVE", now);
    }

    private static PolicyLocation location() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyLocation(LOCATION_ID, TENANT, POLICY_ID, "LOC-001",
                "1200 Industrial Way", "Suite 400", "Akron", "US-OH", "44311", "USA",
                FROM, TO, "ACTIVE", now);
    }
}
