package com.is.aigul.dto;

import com.is.aigul.model.Invoice;
import lombok.Value;

import java.time.LocalDateTime;

@Value
public class InvoiceSummaryResponse {
    Long id;
    String invoiceNumber;
    String operation;
    String status;
    LocalDateTime createdAt;
    LocalDateTime voidedAt;
    Double totalAmount;
    String currency;
    int itemCount;
    String productSummary;
    Long customerId;
    String customerName;
    LocalDateTime assignedAt;

    public static InvoiceSummaryResponse from(Invoice invoice) {
        return new InvoiceSummaryResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getOperation().name(),
                invoice.getStatus().name(),
                invoice.getCreatedAt(),
                invoice.getVoidedAt(),
                invoice.getTotalAmount(),
                invoice.getCurrency(),
                invoice.getItemCount(),
                invoice.getProductSummary(),
                invoice.getCustomer() == null ? null : invoice.getCustomer().getId(),
                invoice.getCustomer() == null ? null : invoice.getCustomer().getName(),
                invoice.getAssignedAt()
        );
    }
}
