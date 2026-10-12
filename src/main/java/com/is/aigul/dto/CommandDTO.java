package com.is.aigul.dto;

import lombok.*;

import java.util.List;


@Data
public class CommandDTO {
    private String operation; // e.g. "SELL", "CREATE", etc.

    // For single-product commands
    private String productName;
    private int quantity;
    private String unit;
    private String location;
    private String updateType;
    private String updatedValue;
    private double originalPrice;
    private double discount;
    private String fromLocation;
    private String toLocation;
    private String message;
    private Long invoiceId;

    // For multi-product sales
    private List<SellItemDTO> items; // NEW: multiple items in one SELL command

    private List<String> warnings;
}
