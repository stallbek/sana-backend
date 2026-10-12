package com.is.aigul.service;

import com.is.aigul.dto.ProductSuggestionItem;
import com.is.aigul.dto.ProductSuggestionRequest;
import com.is.aigul.dto.ProductSuggestionResponse;
import com.is.aigul.repository.ProductNameProjection;
import com.is.aigul.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductSuggestionService {

    private static final int MAX_SPAN_TOKENS = 6;
    private static final int MAX_SPAN_CHARACTERS = 120;
    private static final int MAX_CANDIDATES = 200;
    private static final int NO_MATCH = Integer.MAX_VALUE;
    private static final int[][] INITIAL_VARIANT_RANGES = {
            {0x0030, 0x052F},
            {0x1E00, 0x1FFF},
            {0x2C60, 0x2C7F},
            {0xA640, 0xA7FF},
            {0xAB30, 0xAB6F}
    };
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    private static final Set<String> CONJUNCTIONS = Set.of(
            "and", "or", "и", "или", "жана", "же"
    );
    private static final Map<String, List<String>> INITIAL_EQUIVALENTS = buildInitialEquivalents();

    private final ProductRepository productRepository;
    private final LevenshteinDistance levenshteinDistance = new LevenshteinDistance();

    @Transactional(readOnly = true)
    public ProductSuggestionResponse suggest(ProductSuggestionRequest request, Long userId) {
        List<Token> allTokens = tokenize(request.text());
        Segment segment = activeSegment(request.text(), request.cursor(), allTokens);
        if (segment == null) {
            return ProductSuggestionResponse.empty(request.cursor());
        }

        List<Token> segmentTokens = allTokens.stream()
                .filter(token -> token.start() >= segment.start() && token.end() <= segment.end())
                .filter(token -> !CONJUNCTIONS.contains(token.normalized()))
                .toList();
        List<Span> spans = candidateSpans(segmentTokens, request.cursor());
        if (spans.isEmpty()) {
            return ProductSuggestionResponse.empty(request.cursor());
        }

        List<String> initials = leadingInitials(spans);
        if (initials.isEmpty()) {
            return ProductSuggestionResponse.empty(request.cursor());
        }

        List<ProductNameProjection> candidates = productRepository.findSuggestionCandidates(
                userId,
                initials,
                PageRequest.of(0, MAX_CANDIDATES)
        );
        if (candidates.isEmpty()) {
            return ProductSuggestionResponse.empty(request.cursor());
        }

        SpanRanking selected = selectBestSpan(spans, candidates);
        if (selected == null || selected.matches().isEmpty()) {
            return ProductSuggestionResponse.empty(request.cursor());
        }
        if (selected.matches().get(0).tier() == MatchTier.EXACT) {
            return ProductSuggestionResponse.empty(request.cursor());
        }

        List<RankedMatch> returnedMatches = selected.matches().stream()
                .limit(request.limit())
                .toList();
        List<ProductSuggestionItem> suggestions = returnedMatches.stream()
                .map(match -> new ProductSuggestionItem(match.product().getId(), match.product().getName()))
                .toList();

        return new ProductSuggestionResponse(
                selected.span().start(),
                selected.span().end(),
                commonCompletion(returnedMatches, selected.span()),
                suggestions
        );
    }

    private Segment activeSegment(String text, int cursor, List<Token> tokens) {
        int start = 0;
        int end = text.length();

        for (int index = 0; index < cursor; index++) {
            if (isHardSeparator(text.charAt(index))) {
                start = index + 1;
            }
        }
        for (int index = cursor; index < text.length(); index++) {
            if (isHardSeparator(text.charAt(index))) {
                end = index;
                break;
            }
        }

        for (Token token : tokens) {
            if (!CONJUNCTIONS.contains(token.normalized())) {
                continue;
            }
            if (cursor >= token.start() && cursor <= token.end()) {
                return null;
            }
            if (token.end() <= cursor && token.end() > start) {
                start = token.end();
            } else if (token.start() >= cursor && token.start() < end) {
                end = token.start();
            }
        }
        return new Segment(start, end);
    }

    private boolean isHardSeparator(char value) {
        return value == ',' || value == ';' || value == '&' || value == '\n' || value == '\r';
    }

    private List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String normalized = normalize(matcher.group());
            if (!normalized.isBlank()) {
                tokens.add(new Token(matcher.start(), matcher.end(), matcher.group(), normalized));
            }
        }
        return tokens;
    }

    private List<Span> candidateSpans(List<Token> tokens, int cursor) {
        List<Span> spans = new ArrayList<>();
        for (int startIndex = 0; startIndex < tokens.size(); startIndex++) {
            for (int endIndex = startIndex;
                 endIndex < tokens.size() && endIndex < startIndex + MAX_SPAN_TOKENS;
                 endIndex++) {
                Token first = tokens.get(startIndex);
                Token last = tokens.get(endIndex);
                if (last.end() - first.start() > MAX_SPAN_CHARACTERS) {
                    break;
                }
                if (first.start() > cursor || last.end() < cursor) {
                    continue;
                }

                List<Token> spanTokens = tokens.subList(startIndex, endIndex + 1);
                String normalized = spanTokens.stream()
                        .map(Token::normalized)
                        .reduce((left, right) -> left + " " + right)
                        .orElse("");
                long letterCount = normalized.codePoints().filter(Character::isLetter).count();
                if (letterCount >= 2) {
                    spans.add(new Span(first.start(), last.end(), normalized, List.copyOf(spanTokens)));
                }
            }
        }
        return spans;
    }

    private List<String> leadingInitials(List<Span> spans) {
        Set<String> initials = new LinkedHashSet<>();
        for (Span span : spans) {
            Token first = span.tokens().get(0);
            addInitial(initials, first.normalized());
            addInitial(initials, first.source().toLowerCase(Locale.ROOT));
        }
        return initials.stream().sorted().toList();
    }

    private void addInitial(Collection<String> initials, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        int firstCodePoint = value.codePointAt(0);
        String rawInitial = new String(Character.toChars(firstCodePoint));
        initials.add(rawInitial);

        String normalizedInitial = normalize(rawInitial);
        initials.addAll(INITIAL_EQUIVALENTS.getOrDefault(normalizedInitial, List.of()));
    }

    private SpanRanking selectBestSpan(List<Span> spans, List<ProductNameProjection> candidates) {
        SpanRanking best = null;
        for (Span span : spans) {
            List<RankedMatch> matches = candidates.stream()
                    .filter(candidate -> candidate.getId() != null)
                    .filter(candidate -> candidate.getName() != null && !candidate.getName().isBlank())
                    .map(candidate -> score(span, candidate))
                    .filter(match -> match != null)
                    .sorted(this::compareMatches)
                    .toList();
            if (matches.isEmpty()) {
                continue;
            }

            SpanRanking ranking = new SpanRanking(span, matches);
            if (best == null || compareSpanRankings(ranking, best) < 0) {
                best = ranking;
            }
        }
        return best;
    }

    private RankedMatch score(Span span, ProductNameProjection product) {
        String productName = normalize(product.getName());
        if (productName.isBlank()) {
            return null;
        }

        MatchTier tier;
        int edits = 0;
        if (productName.equals(span.normalized())) {
            tier = MatchTier.EXACT;
        } else if (productName.startsWith(span.normalized())) {
            tier = MatchTier.FULL_PHRASE_PREFIX;
        } else {
            List<String> fragmentTokens = span.tokens().stream().map(Token::normalized).toList();
            List<String> productTokens = Arrays.asList(productName.split(" "));
            int prefixCost = sequentialCost(fragmentTokens, productTokens, false);
            if (prefixCost != NO_MATCH) {
                tier = MatchTier.SEQUENTIAL_WORD_PREFIX;
            } else {
                int fuzzyCost = sequentialCost(fragmentTokens, productTokens, true);
                if (fuzzyCost == NO_MATCH) {
                    return null;
                }
                tier = MatchTier.TYPO_TOLERANT_TOKEN;
                edits = fuzzyCost;
            }
        }

        int remainingCompletion = Math.max(0, productName.length() - span.normalized().length());
        return new RankedMatch(product, tier, edits, remainingCompletion);
    }

    private int sequentialCost(List<String> fragmentTokens, List<String> productTokens, boolean allowFuzzy) {
        return sequentialCost(fragmentTokens, productTokens, allowFuzzy, 0, 0, new HashMap<>());
    }

    private int sequentialCost(
            List<String> fragmentTokens,
            List<String> productTokens,
            boolean allowFuzzy,
            int fragmentIndex,
            int productIndex,
            Map<Long, Integer> memo
    ) {
        if (fragmentIndex == fragmentTokens.size()) {
            return 0;
        }
        if (productTokens.size() - productIndex < fragmentTokens.size() - fragmentIndex) {
            return NO_MATCH;
        }

        long key = (((long) fragmentIndex) << 32) | (productIndex & 0xffffffffL);
        Integer cached = memo.get(key);
        if (cached != null) {
            return cached;
        }

        int best = NO_MATCH;
        String fragmentToken = fragmentTokens.get(fragmentIndex);
        for (int candidateIndex = productIndex; candidateIndex < productTokens.size(); candidateIndex++) {
            int tokenCost = tokenCost(fragmentToken, productTokens.get(candidateIndex), allowFuzzy);
            if (tokenCost == NO_MATCH) {
                continue;
            }
            int remainingCost = sequentialCost(
                    fragmentTokens,
                    productTokens,
                    allowFuzzy,
                    fragmentIndex + 1,
                    candidateIndex + 1,
                    memo
            );
            if (remainingCost != NO_MATCH) {
                best = Math.min(best, tokenCost + remainingCost);
            }
        }
        memo.put(key, best);
        return best;
    }

    private int tokenCost(String fragmentToken, String productToken, boolean allowFuzzy) {
        if (productToken.startsWith(fragmentToken)) {
            return 0;
        }
        if (!allowFuzzy) {
            return NO_MATCH;
        }

        int fragmentLength = fragmentToken.codePointCount(0, fragmentToken.length());
        int allowedEdits = fragmentLength >= 8 ? 2 : fragmentLength >= 4 ? 1 : 0;
        if (allowedEdits == 0 || fragmentToken.codePointAt(0) != productToken.codePointAt(0)) {
            return NO_MATCH;
        }

        int edits = levenshteinDistance.apply(fragmentToken, productToken);
        return edits <= allowedEdits ? edits : NO_MATCH;
    }

    private int compareMatches(RankedMatch left, RankedMatch right) {
        int comparison = Integer.compare(left.tier().rank, right.tier().rank);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(left.edits(), right.edits());
        if (comparison != 0) return comparison;
        comparison = Integer.compare(left.remainingCompletion(), right.remainingCompletion());
        if (comparison != 0) return comparison;
        comparison = left.product().getName().toLowerCase(Locale.ROOT)
                .compareTo(right.product().getName().toLowerCase(Locale.ROOT));
        if (comparison != 0) return comparison;
        return left.product().getId().compareTo(right.product().getId());
    }

    private int compareSpanRankings(SpanRanking left, SpanRanking right) {
        RankedMatch leftBest = left.matches().get(0);
        RankedMatch rightBest = right.matches().get(0);

        int comparison = Integer.compare(leftBest.tier().rank, rightBest.tier().rank);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(leftBest.edits(), rightBest.edits());
        if (comparison != 0) return comparison;
        comparison = Integer.compare(leftBest.remainingCompletion(), rightBest.remainingCompletion());
        if (comparison != 0) return comparison;
        comparison = Integer.compare(right.span().tokens().size(), left.span().tokens().size());
        if (comparison != 0) return comparison;
        comparison = Integer.compare(right.span().end() - right.span().start(), left.span().end() - left.span().start());
        if (comparison != 0) return comparison;
        return Integer.compare(left.span().start(), right.span().start());
    }

    private String commonCompletion(List<RankedMatch> matches, Span span) {
        if (matches.isEmpty()) {
            return null;
        }

        List<String> firstWords = canonicalWords(matches.get(0).product().getName());
        int sharedWordCount = firstWords.size();
        for (int matchIndex = 1; matchIndex < matches.size() && sharedWordCount > 0; matchIndex++) {
            List<String> words = canonicalWords(matches.get(matchIndex).product().getName());
            sharedWordCount = Math.min(sharedWordCount, words.size());
            for (int wordIndex = 0; wordIndex < sharedWordCount; wordIndex++) {
                if (!normalize(firstWords.get(wordIndex)).equals(normalize(words.get(wordIndex)))) {
                    sharedWordCount = wordIndex;
                    break;
                }
            }
        }
        if (sharedWordCount == 0) {
            return null;
        }

        String completion = String.join(" ", firstWords.subList(0, sharedWordCount));
        return normalize(completion).length() > span.normalized().length() ? completion : null;
    }

    private List<String> canonicalWords(String value) {
        List<String> words = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(value);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static Map<String, List<String>> buildInitialEquivalents() {
        Map<String, Set<String>> equivalents = new HashMap<>();
        for (int[] range : INITIAL_VARIANT_RANGES) {
            for (int codePoint = range[0]; codePoint <= range[1]; codePoint++) {
                if (!Character.isLetterOrDigit(codePoint)) {
                    continue;
                }
                String raw = new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT);
                String normalized = normalize(raw);
                if (normalized.codePointCount(0, normalized.length()) == 1) {
                    equivalents.computeIfAbsent(normalized, ignored -> new TreeSet<>()).add(raw);
                }
            }
        }

        Map<String, List<String>> result = new HashMap<>();
        equivalents.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(decomposed.length());
        boolean previousWasSpace = true;
        for (int index = 0; index < decomposed.length(); ) {
            int codePoint = decomposed.codePointAt(index);
            index += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                normalized.appendCodePoint(codePoint);
                previousWasSpace = false;
            } else if (!previousWasSpace) {
                normalized.append(' ');
                previousWasSpace = true;
            }
        }
        int length = normalized.length();
        if (length > 0 && normalized.charAt(length - 1) == ' ') {
            normalized.setLength(length - 1);
        }
        return normalized.toString();
    }

    private enum MatchTier {
        EXACT(1),
        FULL_PHRASE_PREFIX(2),
        SEQUENTIAL_WORD_PREFIX(3),
        TYPO_TOLERANT_TOKEN(4);

        private final int rank;

        MatchTier(int rank) {
            this.rank = rank;
        }
    }

    private record Token(int start, int end, String source, String normalized) {
    }

    private record Span(int start, int end, String normalized, List<Token> tokens) {
    }

    private record Segment(int start, int end) {
    }

    private record RankedMatch(
            ProductNameProjection product,
            MatchTier tier,
            int edits,
            int remainingCompletion
    ) {
    }

    private record SpanRanking(Span span, List<RankedMatch> matches) {
    }
}
