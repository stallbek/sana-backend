package com.is.aigul.service;

import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.exception.UnsupportedCommandException;
import java.util.Locale;
import java.util.Set;

/** Checks both client commands and model output before preprocessing or mutation. */
public final class CommandValidator {
    private static final Set<String> OPERATIONS = Set.of("CREATE", "ADD", "SELL", "MOVE", "UPDATE", "DELETE");
    private static final Set<String> UPDATES = Set.of("price", "quantity", "name", "location");
    private CommandValidator() { }

    public static void validateDraft(CommandDTO cmd) {
        if (cmd == null || cmd.getOperation() == null || cmd.getOperation().isBlank()) {
            throw new IllegalArgumentException("Command operation is required");
        }
        String operation = cmd.getOperation().trim().toUpperCase(Locale.ROOT);
        if ("GET".equals(operation)) throw new UnsupportedCommandException();
        if (!OPERATIONS.contains(operation)) throw new IllegalArgumentException("Unknown command operation");
        text(cmd.getProductName(), 255); text(cmd.getLocation(), 255);
        text(cmd.getFromLocation(), 255); text(cmd.getToLocation(), 255);
        text(cmd.getUnit(), 255); text(cmd.getUpdatedValue(), 255); text(cmd.getUpdateType(), 32);
        text(cmd.getMessage(), 2000);
        if (cmd.getQuantity() < 0) throw new IllegalArgumentException("Quantity must not be negative");
        money(cmd.getOriginalPrice()); money(cmd.getDiscount());
        if (cmd.getItems() != null) {
            if (cmd.getItems().size() > 100) throw new IllegalArgumentException("At most 100 items are supported");
            for (SellItemDTO item : cmd.getItems()) {
                if (item == null) throw new IllegalArgumentException("Item must not be null");
                text(item.getProductName(), 255); text(item.getLocation(), 255); text(item.getUnit(), 255);
                text(item.getCalculationSteps(), 2000);
                if (item.getQuantity() < 0 || item.getPerPack() != null && item.getPerPack() <= 0) {
                    throw new IllegalArgumentException("Invalid item quantity");
                }
                money(item.getOriginalPrice()); money(item.getDiscount());
                money(item.getFinalPrice()); money(item.getTotalPrice());
            }
        }
    }

    public static void validateMutation(CommandDTO cmd) {
        validateDraft(cmd);
        String operation = cmd.getOperation().trim().toUpperCase(Locale.ROOT);
        switch (operation) {
            case "ADD", "SELL" -> {
                if (cmd.getItems() == null || cmd.getItems().isEmpty()) {
                    throw new IllegalArgumentException("At least one item is required");
                }
                for (SellItemDTO item : cmd.getItems()) {
                    required(item.getProductName()); required(item.getLocation());
                    if (item.getQuantity() <= 0) throw new IllegalArgumentException("Item quantity must be positive");
                }
            }
            case "MOVE" -> {
                required(cmd.getProductName()); required(cmd.getFromLocation()); required(cmd.getToLocation());
                if (cmd.getQuantity() <= 0 || cmd.getFromLocation().equalsIgnoreCase(cmd.getToLocation())) {
                    throw new IllegalArgumentException("MOVE requires a positive quantity and different locations");
                }
            }
            case "UPDATE" -> {
                required(cmd.getProductName()); required(cmd.getUpdateType()); required(cmd.getUpdatedValue());
                String update = cmd.getUpdateType().toLowerCase(Locale.ROOT);
                if (!UPDATES.contains(update)) throw new IllegalArgumentException("Unknown update type");
                try {
                    if ("price".equals(update)) money(Double.parseDouble(cmd.getUpdatedValue()));
                    if ("quantity".equals(update) && Integer.parseInt(cmd.getUpdatedValue()) < 0) {
                        throw new IllegalArgumentException("Quantity must not be negative");
                    }
                } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid numeric update"); }
                if ("quantity".equals(update) || "location".equals(update)) required(cmd.getLocation());
            }
            default -> required(cmd.getProductName());
        }
    }

    private static void text(String value, int max) {
        if (value != null && value.length() > max) throw new IllegalArgumentException("Command field exceeds its size limit");
    }
    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required command field is missing");
    }
    private static void money(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 1_000_000_000_000d)) {
            throw new IllegalArgumentException("Monetary values must be finite, nonnegative, and at most one trillion");
        }
    }
}
