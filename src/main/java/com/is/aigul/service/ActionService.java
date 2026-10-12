package com.is.aigul.service;

import com.is.aigul.dto.ActionResponse;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.exception.ProductNotFoundException;
import com.is.aigul.model.*;
import com.is.aigul.model.Product;
import com.is.aigul.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Transactional //imported from spring not jakarta for future
@Service
@RequiredArgsConstructor
public class ActionService {
    private final ProductRepository productRepo;
    private final ProductLocationRepository productLocationRepo;
    private final LogbookService logbookService;
    private final InvoiceService invoiceService;
    private static final String DEFAULT_LOCATION = "nullLocation";

    public ActionResponse create(CommandDTO cmd, Long userId) {
        if (cmd == null || cmd.getProductName() == null || cmd.getProductName().isBlank()) {
            return new ActionResponse(
                    "ERROR",
                    "Invalid command: product name missing",
                    null);
        }

        String productName = cmd.getProductName().trim();
        String locationName =
                (cmd.getLocation() == null || cmd.getLocation().isBlank())
                        ? DEFAULT_LOCATION
                        : cmd.getLocation().trim();

        int quantity = Math.max(cmd.getQuantity(), 0);
        double price = Math.max(cmd.getOriginalPrice(), 0.0);

        Product existing = productRepo.findByNameIgnoreCaseAndUserId(productName, userId)
                .stream()
                .findFirst()
                .orElse(null);

        if (existing == null) {
            Product product = new Product();
            product.setName(productName);
            product.setPrice(price);
            product.setUserId(userId);

            // ensure locations list exists
            if (product.getLocations() == null) {
                product.setLocations(new ArrayList<>());
            }

            // add the first location
            ProductLocation newLoc = new ProductLocation();
            newLoc.setProduct(product);
            newLoc.setLocationName(locationName);
            newLoc.setQuantity(quantity);
            newLoc.setUserId(userId);

            product.getLocations().add(newLoc);

            productRepo.save(product);

            logbookService.logAction(
                    "CREATE",
                    "System",
                    newLoc,
                    quantity,
                    price,
                    "Created new product '" + productName + "' at '" + locationName + "'",
                    product,
                    userId
            );

            return new ActionResponse(
                    "PRODUCT_CREATED",
                    "Product created successfully",
                    Map.of(
                            "productName", productName,
                            "location", locationName,
                            "quantity", quantity,
                            "price", price
                    )
            );
        }

        Product product = existing;
        ProductLocation existingLoc = product.getLocations().stream()
                .filter(l -> java.util.Objects.equals(l.getUserId(), userId))
                .filter(l -> l.getLocationName().equalsIgnoreCase(locationName))
                .findFirst()
                .orElse(null);

        if (existingLoc != null) {
            return new ActionResponse(
                    "ERROR",
                    "Product already exists at this location",
                    Map.of(
                            "productName", productName,
                            "location", locationName
                    )
            );
        }

        ProductLocation newLoc = new ProductLocation();
        newLoc.setProduct(product);
        newLoc.setLocationName(locationName);
        newLoc.setQuantity(quantity);
        newLoc.setUserId(userId);

        product.getLocations().add(newLoc);
        product.setPrice(price); // update price if needed

        productRepo.save(product);

        logbookService.logAction(
                "CREATE",
                "System",
                newLoc,
                quantity,
                price,
                "Added new location '" + locationName + "' to existing product '" + productName + "'",
                product,
                userId
        );

        return new ActionResponse(
                "LOCATION_ADDED",
                "New location added to existing product",
                Map.of(
                        "productName", productName,
                        "location", locationName,
                        "quantity", quantity,
                        "price", price
                )
        );
    }



    public ActionResponse add(CommandDTO cmd, Long userId) {
        // Validate all products exist before adding anything
        List<String> notFound = new ArrayList<>();
        for (SellItemDTO item : cmd.getItems()) {
            boolean exists = productRepo.findByNameIgnoreCaseAndUserId(item.getProductName(), userId)
                    .stream().findFirst().isPresent();
            if (!exists) {
                notFound.add(item.getProductName());
            }
        }
        if (!notFound.isEmpty()) {
            return new ActionResponse(
                    "ERROR",
                    "Products not found: " + notFound + ". Please create them first, then add.",
                    Map.of("missingProducts", notFound)
            );
        }

        List<Map<String, Object>> addedItems = new ArrayList<>();
        List<InvoiceService.InvoiceLineSnapshot> invoiceLines = new ArrayList<>();

        for (SellItemDTO item : cmd.getItems()) {

            Product product = productRepo.findByNameIgnoreCaseAndUserId(item.getProductName(), userId)
                    .stream().findFirst().orElseThrow();

            String loc = (item.getLocation() != null) ? item.getLocation() : DEFAULT_LOCATION;

            //Implementing improved location feature
            ProductLocation location = product.getLocations().stream()
                    .filter(l -> java.util.Objects.equals(l.getUserId(), userId))
                .filter(l -> l.getLocationName().equalsIgnoreCase(loc))
                    .findFirst()
                    .orElseGet(() -> {
                        ProductLocation newLoc = new ProductLocation();
                        newLoc.setProduct(product);
                        newLoc.setLocationName(loc);
                        newLoc.setQuantity(0);
                        newLoc.setUserId(userId);
                        return productLocationRepo.save(newLoc);
                    });

            int before = location.getQuantity();
            int qtyToAdd = item.getQuantity();
            location.setQuantity(Math.addExact(before, qtyToAdd));
            productLocationRepo.save(location);

            addedItems.add(Map.of(
                    "productName", product.getName(),
                    "location", loc,
                    "added", qtyToAdd,
                    "before", before,
                    "after", location.getQuantity()
            ));
            invoiceLines.add(new InvoiceService.InvoiceLineSnapshot(
                    product.getId(),
                    product.getName(),
                    location.getId(),
                    loc,
                    qtyToAdd,
                    item.getUnit(),
                    product.getPrice(),
                    0.0,
                    null,
                    before,
                    location.getQuantity()
            ));

            logbookService.logAction(
                    "ADD",
                    "System",
                    location,
                    qtyToAdd,
                    product.getPrice(),
                    "Added '" + product.getName() + "' (" + qtyToAdd + " pcs) to '" + loc + "'",
                    product,
                    userId
            );
        }

        Invoice invoice = invoiceService.saveInvoice(userId, InvoiceOperation.ADD, null, invoiceLines);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", addedItems.size());
        data.put("items", addedItems);
        addInvoiceMetadata(data, invoice);

        return new ActionResponse(
                "PRODUCTS_ADDED",
                "Products added successfully",
                data
        );
    }


    public ActionResponse sell(CommandDTO cmd, Long userId) {
        List<Map<String, Object>> soldItems = new ArrayList<>();
        List<InvoiceService.InvoiceLineSnapshot> invoiceLines = new ArrayList<>();
        double total = 0.0;
        List<String> postInsights = new ArrayList<>();

        for (SellItemDTO item : cmd.getItems()) {
            Product product = productRepo.findByNameIgnoreCaseAndUserId(item.getProductName(), userId)
                    .stream().findFirst().orElseThrow();
            String loc = item.getLocation() != null ? item.getLocation() : DEFAULT_LOCATION;

            ProductLocation location = product.getLocations().stream()
                    .filter(l -> java.util.Objects.equals(l.getUserId(), userId))
                .filter(l -> l.getLocationName().equalsIgnoreCase(loc))
                    .findFirst().orElseThrow();

            int available = location.getQuantity();
            int requestedQuantity = Math.max(item.getQuantity(), 0);
            int stockDeduction = Math.min(available, requestedQuantity);

            if (requestedQuantity > available) {
                postInsights.add(stockShortageWarning(product.getName(), loc, requestedQuantity, available, 0));
            }

            location.setQuantity(Math.max(0, available - stockDeduction));
            productLocationRepo.save(location);

            int remaining = location.getQuantity();
            if (remaining == 0) {
                postInsights.add("'" + product.getName() + "' is now OUT OF STOCK in '" + loc + "'. Consider restocking soon.");
            } else if (remaining < 5) {
                postInsights.add("Low stock: Only " + remaining + " of '" + product.getName() + "' left in '" + loc + "'.");
            }

            Double itemPrice = item.getOriginalPrice();
            double productPrice = product.getPrice() == null ? 0.0 : product.getPrice();
            double price = itemPrice != null && itemPrice > 0 ? itemPrice : productPrice;
            double discount = item.getDiscount() == null ? 0.0 : item.getDiscount();
            double subtotal = price * requestedQuantity - discount;
            if (!Double.isFinite(subtotal) || subtotal < 0 || !Double.isFinite(total + subtotal)) {
                throw new IllegalArgumentException("Invalid sale amount");
            }
            total += subtotal;

            soldItems.add(Map.of(
                    "productName", product.getName(),
                    "location", loc,
                    "sold", requestedQuantity,
                    "price", price,
                    "discount", discount,
                    "subtotal", subtotal,
                    "before", available,
                    "after", location.getQuantity()
            ));
            invoiceLines.add(new InvoiceService.InvoiceLineSnapshot(
                    product.getId(),
                    product.getName(),
                    location.getId(),
                    loc,
                    requestedQuantity,
                    item.getUnit(),
                    price,
                    discount,
                    subtotal,
                    available,
                    location.getQuantity()
            ));

            String logNote = "Sold '" + product.getName() + "' (" + requestedQuantity + " pcs) from '" + loc + "'";
            if (requestedQuantity > available) {
                logNote += ". Stock overflow: product was sold more than registered stock. Available before sale: "
                        + available + "; recorded sale: " + requestedQuantity + "; remaining stock: 0. Check or add stock later.";
            }

            logbookService.logAction("SELL", "System", location, requestedQuantity, subtotal,
                    logNote, product, userId);
        }

        Invoice invoice = invoiceService.saveInvoice(userId, InvoiceOperation.SELL, total, invoiceLines);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("currency", "som");
        data.put("count", soldItems.size());
        data.put("items", soldItems);
        addInvoiceMetadata(data, invoice);

        ActionResponse response = new ActionResponse(
                "PRODUCTS_SOLD",
                "Sale completed successfully",
                data
        );

        for (String insight : postInsights) {
            response.addInsight(insight);
        }

        return response;
    }

    public ActionResponse undo(CommandDTO cmd, Long userId) {
        if (cmd == null || cmd.getOperation() == null || cmd.getInvoiceId() == null || cmd.getInvoiceId() <= 0) {
            throw new IllegalArgumentException("An invoice ID and operation are required for undo");
        }
        String operation = cmd.getOperation().trim().toUpperCase(Locale.ROOT);
        if (!"ADD".equals(operation) && !"SELL".equals(operation)) {
            throw new com.is.aigul.exception.UnsupportedCommandException();
        }
        // The invoice is locked and validated before any stock changes. Client items are deliberately ignored.
        Invoice invoice = invoiceService.validateUndoInvoice(cmd.getInvoiceId(), userId, InvoiceOperation.valueOf(operation));
        if (invoice.getItems().isEmpty()) throw new IllegalStateException("Invoice has no line items");
        Map<Long, Integer> deltas = new java.util.TreeMap<>();
        for (InvoiceItem item : invoice.getItems()) {
            if (item.getLocationId() == null || item.getStockBefore() < 0 || item.getStockAfter() < 0) {
                throw new IllegalStateException("Invalid invoice snapshot");
            }
            int delta = "SELL".equals(operation)
                    ? Math.subtractExact(item.getStockBefore(), item.getStockAfter())
                    : Math.subtractExact(item.getStockAfter(), item.getStockBefore());
            if (delta < 0) throw new IllegalStateException("Invalid invoice stock delta");
            deltas.merge(item.getLocationId(), delta, Math::addExact);
        }
        List<UndoAdjustment> adjustments = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : deltas.entrySet()) {
            ProductLocation location = productLocationRepo.findOwnedForUpdate(entry.getKey(), userId)
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.NOT_FOUND, "Invoice stock location not found"));
            int updated = "SELL".equals(operation)
                    ? Math.addExact(location.getQuantity(), entry.getValue())
                    : Math.subtractExact(location.getQuantity(), entry.getValue());
            if (updated < 0) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Current stock is insufficient to undo this invoice");
            }
            adjustments.add(new UndoAdjustment(location.getProduct(), location, entry.getValue(), updated));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (UndoAdjustment adjustment : adjustments) {
            adjustment.location.setQuantity(adjustment.updatedQuantity);
            productLocationRepo.save(adjustment.location);
            logCancellation(operation, adjustment.product, adjustment.location, adjustment.quantity, userId);
            items.add(Map.of("productName", adjustment.product.getName(), "location", adjustment.location.getLocationName(),
                    "quantity", adjustment.quantity, "after", adjustment.updatedQuantity));
        }
        invoiceService.voidInvoice(invoice);
        return new ActionResponse("ACTION_CANCELLED", operation + " invoice cancelled successfully",
                Map.of("operation", operation, "count", items.size(), "items", items, "invoiceId", invoice.getId()));
    }

    private String stockShortageWarning(String productName, String locationName, int requested, int available,
            int remainingStock) {
        return "STOCK_SHORTAGE|product=" + productName
                + "|location=" + locationName
                + "|requested=" + requested
                + "|available=" + available
                + "|remainingStock=" + remainingStock;
    }

    private void logCancellation(String cancelledOperation, Product product, ProductLocation location, int quantity, Long userId) {
        logbookService.logAction(
                "CANCELLED",
                "System",
                location,
                quantity,
                0.0,
                "Cancelled " + cancelledOperation + " action for '" + product.getName() + "' (" + quantity + " pcs) at '" + location.getLocationName() + "'",
                product,
                userId
        );
    }

    private void addInvoiceMetadata(Map<String, Object> data, Invoice invoice) {
        if (invoice == null) {
            return;
        }
        data.put("invoiceId", invoice.getId());
        data.put("invoiceNumber", invoice.getInvoiceNumber());
        data.put("invoiceStatus", invoice.getStatus().name());
    }

    private static class UndoAdjustment {
        private final Product product;
        private final ProductLocation location;
        private final int quantity;
        private final int updatedQuantity;

        private UndoAdjustment(Product product, ProductLocation location, int quantity, int updatedQuantity) {
            this.product = product;
            this.location = location;
            this.quantity = quantity;
            this.updatedQuantity = updatedQuantity;
        }
    }

    public ActionResponse move(CommandDTO cmd, Long userId) {
        String fromName = cmd.getFromLocation();
        String toName   = cmd.getToLocation();
        int qty         = cmd.getQuantity();

        Product product = productRepo.findByNameIgnoreCaseAndUserId(cmd.getProductName(), userId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product '" + cmd.getProductName() + "' not found."
                ));


        ProductLocation fromLoc = product.getLocations().stream()
                .filter(l -> java.util.Objects.equals(l.getUserId(), userId))
                .filter(l -> l.getLocationName().equalsIgnoreCase(fromName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Source location '" + fromName + "' does not exist for product '" + product.getName() + "'."
                ));

        if (fromLoc.getQuantity() < qty) {
            return new ActionResponse(
                    "ERROR",
                    "Not enough quantity at source location",
                    null
            );
        }


        ProductLocation toLoc = product.getLocations().stream()
                .filter(l -> java.util.Objects.equals(l.getUserId(), userId))
                .filter(l -> l.getLocationName().equalsIgnoreCase(toName))
                .findFirst()
                .orElseGet(() -> {
                    ProductLocation newLoc = new ProductLocation();
                    newLoc.setProduct(product);
                    newLoc.setLocationName(toName);
                    newLoc.setQuantity(0);
                    newLoc.setUserId(userId);
                    return productLocationRepo.save(newLoc);
                });


        int beforeFrom = fromLoc.getQuantity();
        int beforeTo   = toLoc.getQuantity();

        fromLoc.setQuantity(beforeFrom - qty);
        toLoc.setQuantity(Math.addExact(beforeTo, qty));

        productLocationRepo.save(fromLoc);
        productLocationRepo.save(toLoc);

        // 4️LOG ENTRY
        logbookService.logAction(
                "MOVE",
                "System",
                toLoc,
                qty,
                product.getPrice(),
                "Moved " + qty + "pcs of '" + product.getName() + "' from '" + fromName + "' to '" + toName + "'",
                product,
                userId
        );


        return new ActionResponse(
                "PRODUCT_MOVED",
                "Product moved successfully",
                Map.of(
                        "productName", product.getName(),
                        "moved", qty,
                        "from", Map.of(
                                "location", fromName,
                                "before", beforeFrom,
                                "after", fromLoc.getQuantity()
                        ),
                        "to", Map.of(
                                "location", toName,
                                "before", beforeTo,
                                "after", toLoc.getQuantity()
                        )
                )
        );
    }


    public ActionResponse delete(CommandDTO cmd, Long userId) {
        Product product = productRepo.findByNameIgnoreCaseAndUserId(cmd.getProductName(), userId)
                .stream()
                .findFirst()
                .orElseThrow(ProductNotFoundException::new);
        return archiveProduct(product, userId);
    }

    public ActionResponse delete(Long productId, Long userId) {
        Product product = productRepo.findByIdAndUserId(productId, userId)
                .orElseThrow(ProductNotFoundException::new);
        return archiveProduct(product, userId);
    }

    private ActionResponse archiveProduct(Product product, Long userId) {
        product.setDeletedAt(LocalDateTime.now());
        productRepo.save(product);
        logbookService.logAction("DELETE", "System", null, 0, 0.0,
                "Deleted product '" + product.getName() + "'", product, userId);

        return new ActionResponse(
                "PRODUCT_DELETED",
                "Product deleted successfully",
                Map.of(
                        "productId", product.getId(),
                        "productName", product.getName()
                )
        );
    }

    public ActionResponse update(CommandDTO cmd, Long userId){

        Product product = productRepo.findByNameIgnoreCaseAndUserId(cmd.getProductName(), userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product '" + cmd.getProductName() + "' not found."
                ));

        String updateType = cmd.getUpdateType().toLowerCase();
        String newValue = cmd.getUpdatedValue();

        ProductLocation loc = null;
        if (updateType.equals("quantity") || updateType.equals("location")) {

            loc = productLocationRepo.findByProductAndLocationNameIgnoreCaseAndUserId(product, cmd.getLocation(), userId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Location '" + cmd.getLocation() + "' not found for product '" + product.getName() + "'."
                    ));
        }

        String logMessage;
        String response;

        switch (updateType) {
            case "price" -> {
                double oldPrice = product.getPrice();
                double updatedPrice = Double.parseDouble(newValue);

                product.setPrice(updatedPrice);
                productRepo.save(product);

                logMessage = "Updated price of '" + product.getName() + "' "
                        + oldPrice + " → " + updatedPrice;

                logbookService.logAction("UPDATE", "System", null, 0, updatedPrice, logMessage, product, userId);

                return new ActionResponse(
                        "PRICE_UPDATED",
                        "Price updated successfully",
                        null
                );
            }

            case "name" -> {
                String oldName = product.getName();
                product.setName(newValue);
                productRepo.save(product);

                logMessage = "Renamed product: '" + oldName + "' → '" + newValue + "'";
                logbookService.logAction("UPDATE", "System", null, 0, 0.0, logMessage, product, userId);

                return new ActionResponse(
                        "PRODUCT_RENAMED",
                        "Product renamed successfully",
                        null
                );
            }

            case "quantity" -> {
                int oldQty = loc.getQuantity();
                int newQty = Integer.parseInt(newValue);

                loc.setQuantity(newQty);
                productLocationRepo.save(loc);

                logMessage = "Updated quantity for '" + product.getName()
                        + "' at '" + loc.getLocationName()
                        + "' " + oldQty + " → " + newQty;

                logbookService.logAction("UPDATE", "System", loc, newQty, 0.0, logMessage, product, userId);

                return new ActionResponse(
                        "QUANTITY_UPDATED",
                        "Quantity updated successfully",
                        null
                );
            }

            case "location" -> {
                String oldLocation = loc.getLocationName();
                String newLocation = newValue;

                if (oldLocation.equalsIgnoreCase(newLocation)) {
                    return new ActionResponse(
                            "ERROR",
                            "Location is already '" + newLocation + "'",
                            null
                    );
                }

                loc.setLocationName(newLocation);
                productLocationRepo.save(loc);

                logMessage = "Moved product '" + product.getName()
                        + "' location: '" + oldLocation + "' → '" + newLocation + "'";

                logbookService.logAction("UPDATE", "System", loc, 0, 0.0, logMessage, product, userId);

                return new ActionResponse(
                        "LOCATION_RENAMED",
                        "Location renamed successfully",
                        null
                );
            }

            default -> throw new IllegalArgumentException("Unknown update type: " + updateType);
        }
    }


}
