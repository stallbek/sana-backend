package com.is.aigul.dto;
import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
public class LogbookDTO {
    private Long id;
    private String actionType;
    private LocalDateTime timestamp;
    private int quantity;
    private Double amount;
    private String notes;

    private String productName;
    private String locationName;

    private Long customerId;
    private String customerName;
    private Long paymentEntryId;
    private String currency;
}
