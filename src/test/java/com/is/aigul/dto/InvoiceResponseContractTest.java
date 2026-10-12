package com.is.aigul.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.is.aigul.model.Customer;
import com.is.aigul.model.Invoice;
import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceResponseContractTest {

    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void summaryKeepsExistingFieldsAndAddsNullableCustomerFields() {
        Invoice invoice = invoice();

        JsonNode json = objectMapper.valueToTree(InvoiceSummaryResponse.from(invoice));

        assertThat(json.get("id").asLong()).isEqualTo(44L);
        assertThat(json.get("invoiceNumber").asText()).isEqualTo("INV-20260716-TEST");
        assertThat(json.get("operation").asText()).isEqualTo("SELL");
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.get("totalAmount").asDouble()).isEqualTo(250.0);
        assertThat(json.get("currency").asText()).isEqualTo("som");
        assertThat(json.get("itemCount").asInt()).isZero();
        assertThat(json.get("productSummary").asText()).isEqualTo("Atlas");
        assertThat(json.has("customerId")).isTrue();
        assertThat(json.get("customerId").isNull()).isTrue();
        assertThat(json.get("customerName").isNull()).isTrue();
        assertThat(json.get("assignedAt").isNull()).isTrue();
    }

    @Test
    void detailIncludesCurrentAssignedCustomerNameWithoutPhone() {
        Invoice invoice = invoice();
        Customer customer = new Customer();
        customer.setId(9L);
        customer.setName("Aizada");
        customer.setPhone("+996555123456");
        invoice.setCustomer(customer);
        invoice.setAssignedAt(LocalDateTime.of(2026, 7, 16, 12, 0));

        JsonNode json = objectMapper.valueToTree(InvoiceDetailResponse.from(invoice));

        assertThat(json.get("customerId").asLong()).isEqualTo(9L);
        assertThat(json.get("customerName").asText()).isEqualTo("Aizada");
        assertThat(json.has("customerPhone")).isFalse();
        assertThat(json.has("phone")).isFalse();
    }

    private Invoice invoice() {
        Invoice invoice = new Invoice();
        invoice.setId(44L);
        invoice.setInvoiceNumber("INV-20260716-TEST");
        invoice.setUserId(7L);
        invoice.setOperation(InvoiceOperation.SELL);
        invoice.setStatus(InvoiceStatus.ACTIVE);
        invoice.setCreatedAt(LocalDateTime.of(2026, 7, 16, 10, 0));
        invoice.setTotalAmount(250.0);
        invoice.setCurrency("som");
        invoice.setProductSummary("Atlas");
        return invoice;
    }
}
