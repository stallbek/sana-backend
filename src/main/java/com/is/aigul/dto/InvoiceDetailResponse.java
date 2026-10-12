package com.is.aigul.dto;

import com.is.aigul.model.Invoice;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
public class InvoiceDetailResponse {
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
    List<InvoiceItemResponse> items;
    Long customerId;
    String customerName;
    LocalDateTime assignedAt;

    public static InvoiceDetailResponse from(Invoice invoice) {
        return new InvoiceDetailResponse(
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
                invoice.getItems().stream().map(InvoiceItemResponse::from).toList(),
                invoice.getCustomer() == null ? null : invoice.getCustomer().getId(),
                invoice.getCustomer() == null ? null : invoice.getCustomer().getName(),
                invoice.getAssignedAt()
        );
    }
}
