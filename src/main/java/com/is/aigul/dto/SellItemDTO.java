package com.is.aigul.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class SellItemDTO {
    private String productName;    // "атлас", "сигареты"
    private int quantity;          // Final quantity after packs/math
    private String unit;           // "meters", "packs"
    private String location;       // Warehouse
    private Integer perPack;       // e.g. 4 meters per pack
    private Double discount;       // Discount per unit
    private Double originalPrice;  // Price before discount
    private Double finalPrice;     // Price after discount
    private Double totalPrice;     // Quantity × FinalPrice
    private String calculationSteps; // Transparent steps
}
