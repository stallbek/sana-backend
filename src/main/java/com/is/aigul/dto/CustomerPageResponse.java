package com.is.aigul.dto;

import lombok.Value;

import java.util.List;

@Value
public class CustomerPageResponse {
    List<CustomerSummaryResponse> items;
    int page;
    int size;
    long totalItems;
    int totalPages;
    boolean hasNext;
}
