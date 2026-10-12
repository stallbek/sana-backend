package com.is.aigul.dto;

public record CustomerPaymentMutationResponse(
        CustomerPaymentEntryResponse payment,
        CustomerSummaryResponse customer
) {
}
