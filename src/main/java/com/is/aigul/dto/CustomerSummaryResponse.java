package com.is.aigul.dto;

import com.is.aigul.model.Customer;
import lombok.Value;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Value
public class CustomerSummaryResponse {
    Long id;
    String name;
    String phone;
    Double totalOwed;
    Double grossAssignedAmount;
    Double netPaidAmount;
    Double creditBalance;
    String currency;
    long assignedInvoiceCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static CustomerSummaryResponse from(
            Customer customer,
            Double grossAssignedAmount,
            Double netPaidAmount,
            long assignedInvoiceCount
    ) {
        double gross = money(grossAssignedAmount);
        double paid = money(netPaidAmount);
        return new CustomerSummaryResponse(
                customer.getId(),
                customer.getName(),
                customer.getPhone(),
                money(Math.max(gross - paid, 0.0)),
                gross,
                paid,
                money(Math.max(paid - gross, 0.0)),
                "som",
                assignedInvoiceCount,
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
    }

    private static double money(Double value) {
        if (value == null) {
            return 0.0;
        }
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
