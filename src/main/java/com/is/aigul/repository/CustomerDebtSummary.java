package com.is.aigul.repository;

public interface CustomerDebtSummary {
    Long getCustomerId();

    Double getTotalOwed();

    long getAssignedInvoiceCount();
}
