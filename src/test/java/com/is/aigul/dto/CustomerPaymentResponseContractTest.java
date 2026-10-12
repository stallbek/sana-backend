package com.is.aigul.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.is.aigul.model.Customer;
import com.is.aigul.model.CustomerPaymentEntry;
import com.is.aigul.model.CustomerPaymentEntryType;
import com.is.aigul.model.CustomerPaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerPaymentResponseContractTest {
    private final ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    @Test
    void customerSummaryKeepsExistingFieldsAndAddsLedgerBalances() {
        Customer customer = customer();

        JsonNode json = objectMapper.valueToTree(
                CustomerSummaryResponse.from(customer, 10000.0, 5000.0, 2));

        assertThat(json.get("id").asLong()).isEqualTo(9L);
        assertThat(json.get("name").asText()).isEqualTo("Aizada");
        assertThat(json.get("phone").asText()).isEqualTo("+996555123456");
        assertThat(json.get("totalOwed").asDouble()).isEqualTo(5000.0);
        assertThat(json.get("grossAssignedAmount").asDouble()).isEqualTo(10000.0);
        assertThat(json.get("netPaidAmount").asDouble()).isEqualTo(5000.0);
        assertThat(json.get("creditBalance").asDouble()).isZero();
        assertThat(json.get("currency").asText()).isEqualTo("som");
        assertThat(json.get("assignedInvoiceCount").asLong()).isEqualTo(2);
        assertThat(json.has("createdAt")).isTrue();
        assertThat(json.has("updatedAt")).isTrue();
    }

    @Test
    void paymentEntryExposesOnlyThePublicLedgerContract() {
        CustomerPaymentEntry entry = new CustomerPaymentEntry();
        entry.setId(41L);
        entry.setUserId(7L);
        entry.setCustomer(customer());
        entry.setType(CustomerPaymentEntryType.PAYMENT);
        entry.setAmount(new BigDecimal("5000.00"));
        entry.setCurrency("som");
        entry.setMethod(CustomerPaymentMethod.CARD);
        entry.setOccurredAt(LocalDateTime.of(2026, 7, 28, 10, 0));
        entry.setCreatedAt(LocalDateTime.of(2026, 7, 28, 10, 1));
        entry.setNote("counter");

        JsonNode json = objectMapper.valueToTree(CustomerPaymentEntryResponse.from(entry));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "id", "type", "amount", "currency", "method", "occurredAt",
                "createdAt", "note", "reversalOfPaymentId");
        assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(json.get("method").asText()).isEqualTo("CARD");
        assertThat(json.get("reversalOfPaymentId").isNull()).isTrue();
        assertThat(json.has("idempotencyKey")).isFalse();
        assertThat(json.has("requestFingerprint")).isFalse();
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(9L);
        customer.setUserId(7L);
        customer.setName("Aizada");
        customer.setPhone("+996555123456");
        customer.setCreatedAt(LocalDateTime.of(2026, 7, 20, 9, 0));
        customer.setUpdatedAt(customer.getCreatedAt());
        return customer;
    }
}
