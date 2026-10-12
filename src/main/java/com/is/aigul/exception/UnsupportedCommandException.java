package com.is.aigul.exception;

public class UnsupportedCommandException extends IllegalArgumentException {
    public UnsupportedCommandException() {
        super("Use the product, logbook, and analytics APIs for queries; only ADD and SELL invoices support undo");
    }
}
