package com.eia.policy.expert;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Tenant-wide premium receivables worklist. Unlike the per-policy billing
 * routes this read spans every policy in the tenant: it joins
 * {@code policy_installment}, {@code policy_billing_plan}, and {@code policy}
 * so the plan number, policy number, and insured party are available in one
 * query. The service is fixed to the DIRECT book. The public route is also
 * exposed under the service's internal prefix so the Policy domain adapter can
 * reach the same read.
 */
@RestController
@RequestMapping({
        "/api/v1/billing/installments",
        "/internal/v1/billing/installments"
})
public class BillingReceivablesController {
    private final BillingService service;

    public BillingReceivablesController(BillingService service) {
        this.service = service;
    }

    @GetMapping
    public ReceivablesView list(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "dueBefore", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        RequestScope.requireDirectBook(book);
        return service.listReceivables(
                RequestScope.tenant(tenantId, tenantHeader), status, dueBefore, limit);
    }
}
