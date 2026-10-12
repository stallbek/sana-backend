package com.is.aigul.dto;

import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import lombok.Value;

import java.time.LocalDateTime;

@Value
public class InvoiceSearchRequest {
    InvoiceOperation operation;
    InvoiceStatus status;
    String product;
    String q;
    LocalDateTime start;
    LocalDateTime end;
    Long customerId;
}
