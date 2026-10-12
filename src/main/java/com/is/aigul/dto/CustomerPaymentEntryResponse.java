package com.is.aigul.dto;

import com.is.aigul.model.CustomerPaymentEntry;
import com.is.aigul.model.CustomerPaymentEntryType;
import com.is.aigul.model.CustomerPaymentMethod;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
public class CustomerPaymentEntryResponse {
    Long id;
    CustomerPaymentEntryType type;
    BigDecimal amount;
    String currency;
    CustomerPaymentMethod method;
    LocalDateTime occurredAt;
    LocalDateTime createdAt;
    String note;
    Long reversalOfPaymentId;

    public static CustomerPaymentEntryResponse from(CustomerPaymentEntry entry) {
        return new CustomerPaymentEntryResponse(
                entry.getId(),
                entry.getType(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getMethod(),
                entry.getOccurredAt(),
                entry.getCreatedAt(),
                entry.getType() == CustomerPaymentEntryType.REVERSAL
                        ? entry.getReversalReason() : entry.getNote(),
                entry.getReversalOfPayment() == null
                        ? null : entry.getReversalOfPayment().getId()
        );
    }
}
