package com.is.aigul.repository;

import java.math.BigDecimal;

public interface CustomerPaymentBalanceSummary {
    Long getCustomerId();
    BigDecimal getNetPaidAmount();
}
