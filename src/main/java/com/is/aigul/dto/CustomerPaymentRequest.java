package com.is.aigul.dto;

import com.is.aigul.model.CustomerPaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CustomerPaymentRequest(
        BigDecimal amount,
        CustomerPaymentMethod method,
        LocalDateTime paidAt,
        String note
) {
}
