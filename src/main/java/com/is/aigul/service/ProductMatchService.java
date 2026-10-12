package com.is.aigul.service;

import com.is.aigul.model.LocationNames;
import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductMatchService {

    private final ProductRepository productRepository;
    private final LevenshteinDistance levenshtein = new LevenshteinDistance();
    private final LocationNamesRepository locationNamesRepository;


    private String normalize(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .trim()
                .replace('й', 'и')
                .replace('ы', 'и')
                .replaceAll("[^а-яa-z0-9 ]", "")
                .replace('ё', 'е');
        return normalized;
    }

    private String sortTokens(String input) {
        String[] tokens = input.split("\\s+");
        java.util.Arrays.sort(tokens);
        return String.join(" ", tokens);
    }

    private int percentSimilarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 100;
        int dist = levenshtein.apply(a, b);
        return (int) ((1 - (double) dist / maxLen) * 100);
    }

    private int similarity(String a, String b) {
        a = normalize(a);
        b = normalize(b);
        return Math.max(percentSimilarity(a, b), percentSimilarity(sortTokens(a), sortTokens(b)));
    }

    public List<Product> findAllBestMatches(String rawName, Long userId) {
        if (rawName == null || rawName.isBlank()) return List.of();
        String normalizedName = normalize(rawName);
        List<Product> allProducts = productRepository.findByUserId(userId);

        List<ProductScore> scored = new ArrayList<>();
        int threshold = 70;

        for (Product p : allProducts) {
            if (p.getName() == null) continue;
            int score = similarity(normalizedName, p.getName());
            if (score >= threshold) scored.add(new ProductScore(p, score));
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        List<Product> results = new ArrayList<>();
        for (ProductScore ps : scored) results.add(ps.product);

        return results;
    }

    public Product findClosestProductNoThreshold(String rawName, Long userId) {
        if (rawName == null || rawName.isBlank()) return null;
        String normalizedName = normalize(rawName);
        List<Product> all = productRepository.findByUserId(userId);

        Product best = null;
        double bestScore = -1;

        for (Product p : all) {
            if (p.getName() == null || p.getName().isBlank()) continue;
            String normalizedDbName = normalize(p.getName());
            int dist = levenshtein.apply(normalizedName, normalizedDbName);
            double ratio = 1 - (double) dist / Math.max(normalizedName.length(), normalizedDbName.length());
            if (ratio > bestScore) {
                bestScore = ratio;
                best = p;
            }
        }

        return best;
    }

    public Product findClosestProductWithThreshold(String rawName, double minSimilarity, Long userId) {
        if (rawName == null || rawName.isBlank()) return null;

        String normalizedName = normalize(rawName);
        List<Product> all = productRepository.findByUserId(userId);

        Product best = null;
        double bestScore = minSimilarity;  // e.g. 0.80

        for (Product p : all) {
            if (p.getName() == null || p.getName().isBlank()) continue;

            String normalizedDbName = normalize(p.getName());

            int dist = levenshtein.apply(normalizedName, normalizedDbName);
            double ratio =
                    1.0 - (double) dist / Math.max(normalizedName.length(), normalizedDbName.length());

            if (ratio > bestScore) {
                bestScore = ratio;
                best = p;
            }
        }

        return best;
    }


    public String findClosestLocationNoThreshold(Product product, String rawLoc) {
        if (product == null || rawLoc == null || rawLoc.isBlank()) return null;
        List<ProductLocation> locs = product.getLocations();
        if (locs == null || locs.isEmpty()) return null;

        String normInput = normalize(rawLoc);
        String bestMatch = null;
        double bestScore = 0;

        for (ProductLocation loc : locs) {
            String normLoc = normalize(loc.getLocationName());
            if (normLoc.isBlank()) continue;
            int dist = levenshtein.apply(normInput, normLoc);
            double score = 1 - (double) dist / Math.max(normInput.length(), normLoc.length());
            if (score > bestScore) {
                bestScore = score;
                bestMatch = loc.getLocationName();
            }
        }

        return bestMatch;
    }


    public String findClosestLocationWithThreshold(Product product, String rawLoc, int minPercent) {
        if (product == null || rawLoc == null || rawLoc.isBlank()) return null;
        List<ProductLocation> locs = product.getLocations();
        if (locs == null || locs.isEmpty()) return null;

        // Normalize input once
        String normInput = normalize(rawLoc);

        String bestMatch = null;
        int bestScore = -1;

        for (ProductLocation loc : locs) {
            String dbLoc = loc.getLocationName();
            if (dbLoc == null || dbLoc.isBlank()) continue;

            // Exact (case-insensitive) short-circuit
            if (dbLoc.equalsIgnoreCase(rawLoc)) {
                return dbLoc;
            }

            // Use same similarity heuristic as for products:
            // max(levenshtein ratio on normalized, ratio on token-sorted normalized)
            int score = similarity(normInput, dbLoc);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = dbLoc;
            }
        }

        // Enforce threshold
        return (bestScore >= minPercent) ? bestMatch : null;
    }

    public String findClosestGlobalLocation(String rawInput, int minPercent, Long userId) {
        if (rawInput == null || rawInput.isBlank()) return null;

        // normalize input
        String normInput = normalize(rawInput);

        // load all possible global locations
        List<LocationNames> all = locationNamesRepository.findByUserId(userId);
        if (all == null || all.isEmpty()) return null;

        String bestMatch = null;
        int bestScore = -1;

        for (LocationNames loc : all) {
            String dbName = loc.getLocationName();
            if (dbName == null || dbName.isBlank()) continue;

            // exact match shortcut
            if (dbName.equalsIgnoreCase(rawInput)) {
                return dbName;
            }

            int score = similarity(normInput, dbName);

            if (score > bestScore) {
                bestScore = score;
                bestMatch = dbName;
            }
        }

        return (bestScore >= minPercent) ? bestMatch : null;
    }

    public String findClosestGlobalLocationNoThreshold(String rawInput, Long userId) {
        if (rawInput == null || rawInput.isBlank()) return null;

        List<LocationNames> all = locationNamesRepository.findByUserId(userId);
        if (all == null || all.isEmpty()) return null;

        String normInput = normalize(rawInput);
        String bestMatch = null;
        double bestScore = -1; // allow empty strings to be ignored

        for (LocationNames loc : all) {
            String dbName = loc.getLocationName();
            if (dbName == null || dbName.isBlank()) continue;

            String normDb = normalize(dbName);
            if (normDb.isBlank()) continue;

            // Levenshtein distance
            int dist = levenshtein.apply(normInput, normDb);

            // Ratio 0.0–1.0
            double score = 1 - (double) dist / Math.max(normInput.length(), normDb.length());

            if (score > bestScore) {
                bestScore = score;
                bestMatch = dbName; // return original, not normalized
            }
        }

        return bestMatch;  // always returns the closest one
    }

    private static class ProductScore {
        Product product;
        int score;
        ProductScore(Product product, int score) {
            this.product = product;
            this.score = score;
        }
    }
}
