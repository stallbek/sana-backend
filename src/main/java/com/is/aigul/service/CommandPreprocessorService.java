package com.is.aigul.service;

import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CommandPreprocessorService {

    private final ProductMatchService productMatchService;
    private final UserService userService;

    public CommandDTO preprocess(CommandDTO cmd, Long userId) {
        CommandValidator.validateDraft(cmd);

        String operation = cmd.getOperation().trim().toUpperCase(java.util.Locale.ROOT);
        cmd.setOperation(operation);

        if ("CREATE".equals(operation)){
            return processCreate(cmd, userId);
        }

        else if ("GET".equals(operation)) {
            return processGet(cmd, userId);
        }

        else if ("ADD".equals(operation)) {
            return processAdd(cmd, userId);
        }

        else if ("SELL".equals(operation)) {
            return processSell(cmd, userId);
        }

        else if ("MOVE".equals(operation)) {
            return processMove(cmd, userId);
        }

        else if ("UPDATE".equals(operation)) {
            return processUpdate(cmd, userId);
        }

        else if ("DELETE".equals(operation)) {
            return processDelete(cmd, userId);
        }

        else {
            return cmd;
        }
    }


    private CommandDTO processCreate(CommandDTO cmd, Long userId){
        if (cmd == null || cmd.getOperation() == null || cmd.getProductName() == null || cmd.getProductName().isBlank())
            return cmd;

        String location = cmd.getLocation();
        boolean hasNoLocation = (location == null || location.isBlank());

        if (hasNoLocation){
            cmd.setLocation(getDefLocation(userId));
            return cmd;
        }
        String best = productMatchService.findClosestGlobalLocation(location, 55, userId);

        // Safety fallback
        if (best == null || best.isBlank()) {
            cmd.setLocation(getDefLocation(userId));
        }
        else {
            cmd.setLocation(best);
        }

        return cmd;
    }


    private CommandDTO processGet(CommandDTO cmd, Long userId) {
        return cmd;
    }


    private CommandDTO processAdd(CommandDTO cmd, Long userId){
        if (cmd.getItems() != null && !cmd.getItems().isEmpty()) {
            for (SellItemDTO item : cmd.getItems()) {
                String rawName = item.getProductName();
                boolean hasName = rawName != null && !rawName.isBlank();

                if (hasName) {
                    java.util.List<Product> matches = productMatchService.findAllBestMatches(rawName, userId);
                    if (!matches.isEmpty()) {
                        item.setProductName(matches.get(0).getName());
                    } else {
                        addWarning(cmd, "Product '" + rawName + "' was not found. Create it first, or change this card to CREATE.");
                    }
                }

                String rawLoc = item.getLocation();
                boolean hasLoc = rawLoc != null && !rawLoc.isBlank();

                if (!hasLoc) {
                    item.setLocation(getDefLocation(userId));
                    continue;
                }

                String globalBest = productMatchService.findClosestGlobalLocation(rawLoc, 70, userId);
                if (globalBest != null) {
                    item.setLocation(globalBest);
                }
            }
        }
        return cmd;
    }


    private CommandDTO processSell(CommandDTO cmd, Long userId) {
        double total = 0;

        if (cmd.getItems() == null || cmd.getItems().isEmpty()) {
            return cmd;
        }

        for (SellItemDTO item : cmd.getItems()) {

            String rawName = item.getProductName();
            String rawLoc  = item.getLocation();

            boolean hasName = rawName != null && !rawName.isBlank();
            boolean hasLoc  = rawLoc != null && !rawLoc.isBlank();

            Product matchedProduct = null;

            if (hasName) {
                matchedProduct = productMatchService.findClosestProductNoThreshold(rawName, userId);
                if (matchedProduct != null) {
                    item.setProductName(matchedProduct.getName());
                }
            }

            if (!hasLoc) {
                // No location → default
                item.setLocation(getDefLocation(userId));
            }
            else if (matchedProduct != null) {
                // Only local fuzzy match for SELL
                String localBest = productMatchService.findClosestLocationNoThreshold(
                        matchedProduct,
                        rawLoc
                );

                if (localBest != null) {
                    item.setLocation(localBest);
                }
                else {
                    item.setLocation(getDefLocation(userId));
                }
            }
            else {
                item.setLocation(getDefLocation(userId));
            }

            double discount = (item.getDiscount() == null) ? 0.0 : item.getDiscount();

            if (matchedProduct != null &&
                    (item.getOriginalPrice() == null || item.getOriginalPrice() <= 0)) {
                item.setOriginalPrice(matchedProduct.getPrice());
            }

            double originalPrice = (item.getOriginalPrice() == null) ? 0.0 : item.getOriginalPrice();

            if (matchedProduct == null) {
                addWarning(cmd, "Product was not found in your inventory");
                continue;
            }

            ProductLocation location = matchedProduct.getLocations().stream()
                    .filter(l -> l.getLocationName().equalsIgnoreCase(item.getLocation()))
                    .findFirst().orElse(null);

            if (location == null) {
                if (cmd.getWarnings() == null) cmd.setWarnings(new java.util.ArrayList<>());
                cmd.getWarnings().add("Location Error: Product '" + matchedProduct.getName() + "' does not exist at location '" + item.getLocation() + "'.");
                item.setQuantity(0); // Safely adjust for preview
            } else {
                int remaining = location.getQuantity() - item.getQuantity();

                if (remaining < 0) {
                     if (cmd.getWarnings() == null) cmd.setWarnings(new java.util.ArrayList<>());
                     cmd.getWarnings().add(stockShortageWarning(matchedProduct.getName(), location.getLocationName(),
                             item.getQuantity(), location.getQuantity(), 0));
                } else if (remaining < 5) {
                     if (cmd.getWarnings() == null) cmd.setWarnings(new java.util.ArrayList<>());
                     cmd.getWarnings().add("Low Stock Alert: You will only have " + remaining + " of '" + matchedProduct.getName() + "' left in '" + location.getLocationName() + "' after this transaction.");
                }
            }

            double lineTotal = item.getQuantity() * originalPrice - discount;
            item.setTotalPrice(lineTotal);

            total += lineTotal;
        }

        cmd.setOriginalPrice(total);
        return cmd;
    }


    private CommandDTO processMove(CommandDTO cmd, Long userId) {
        String rawFrom = cmd.getFromLocation();
        String rawTo   = cmd.getToLocation();
        String rawName = cmd.getProductName();

        boolean hasFrom = rawFrom != null && !rawFrom.isBlank();
        boolean hasTo   = rawTo != null && !rawTo.isBlank();
        boolean hasName   = rawName != null && !rawName.isBlank();

        if (hasName){
            Product matchedProduct = productMatchService.findClosestProductNoThreshold(rawName, userId);
            if (matchedProduct != null) {
                cmd.setProductName(matchedProduct.getName());
            }
        }

        if (!hasFrom) {
            cmd.setFromLocation(getDefLocation(userId));
        }
        else {
            String bestFrom = productMatchService.findClosestGlobalLocationNoThreshold(rawFrom, userId);

            if (bestFrom != null) {
                cmd.setFromLocation(bestFrom);
            }
            else {
                cmd.setFromLocation(getDefLocation(userId));
            }
        }

        if (hasTo) {
            String bestTo = productMatchService.findClosestGlobalLocationNoThreshold(rawTo, userId);

            if (bestTo != null) {
                cmd.setToLocation(bestTo);
            }
            else {
                // if fuzzy fails, keep user typing (common for new locations)
                cmd.setToLocation(rawTo);
            }
        }

        return cmd;
    }

    private CommandDTO processUpdate(CommandDTO cmd, Long userId) {
        String rawName = cmd.getProductName();
        String rawLoc  = cmd.getLocation();

        boolean hasName = rawName != null && !rawName.isBlank();
        boolean hasLoc  = rawLoc != null && !rawLoc.isBlank();


        Product matched = null;
        if (hasName) {
            matched = productMatchService.findClosestProductNoThreshold(rawName, userId);

            if (matched != null) {
                cmd.setProductName(matched.getName());
            }
        }

        if (!hasLoc) {
            cmd.setLocation(getDefLocation(userId));

        }
        else {
            String bestLoc = productMatchService.findClosestLocationNoThreshold(matched, rawLoc);
            cmd.setLocation(bestLoc);
        }

        return cmd;
    }

    private CommandDTO processDelete(CommandDTO cmd, Long userId){
        return cmd;
    }

    private String getDefLocation(Long userId) {
        String loc = userService.getDefaultLocation(userId);
        return (loc == null || loc.isBlank()) ? null : loc;
    }

    private void addWarning(CommandDTO cmd, String warning) {
        if (cmd.getWarnings() == null) {
            cmd.setWarnings(new java.util.ArrayList<>());
        }
        cmd.getWarnings().add(warning);
    }

    private String stockShortageWarning(String productName, String locationName, int requested, int available,
            int remainingStock) {
        return "STOCK_SHORTAGE|product=" + productName
                + "|location=" + locationName
                + "|requested=" + requested
                + "|available=" + available
                + "|remainingStock=" + remainingStock;
    }


}
