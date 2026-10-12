package com.is.aigul.dto;

import lombok.Value;

import java.util.List;

@Value
public class CustomerPaymentPageResponse {
    List<CustomerPaymentEntryResponse> items;
    int page;
    int size;
    long totalItems;
    int totalPages;
    boolean hasNext;
}
