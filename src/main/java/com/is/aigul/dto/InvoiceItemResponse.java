package com.is.aigul.dto;

import com.is.aigul.model.InvoiceItem;
import lombok.Value;

@Value
public class InvoiceItemResponse {
    Long id;
    Long productId;
    String productName;
    Long locationId;
    String locationName;
    int quantity;
    String unit;
    Double unitPrice;
    Double discount;
    Double subtotal;
    int stockBefore;
    int stockAfter;

    public static InvoiceItemResponse from(InvoiceItem item) {
        return new InvoiceItemResponse(
                item.getId(),
                item.getProductId(),
                item.getProductName(),
                item.getLocationId(),
                item.getLocationName(),
                item.getQuantity(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getDiscount(),
                item.getSubtotal(),
                item.getStockBefore(),
                item.getStockAfter()
        );
    }
}
