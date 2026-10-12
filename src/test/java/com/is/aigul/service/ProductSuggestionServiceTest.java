package com.is.aigul.service;

import com.is.aigul.dto.ProductSuggestionContext;
import com.is.aigul.dto.ProductSuggestionRequest;
import com.is.aigul.dto.ProductSuggestionResponse;
import com.is.aigul.repository.ProductNameProjection;
import com.is.aigul.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProductSuggestionServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ProductRepository productRepository;

    private ProductSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new ProductSuggestionService(productRepository);
        lenient().when(productRepository.findSuggestionCandidates(eq(USER_ID), anyCollection(), any(Pageable.class)))
                .thenReturn(atlassFamily());
    }

    @Test
    void atlasFindsAtlassFamily() {
        ProductSuggestionResponse response = suggest("atlas");

        assertThat(response.suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly(
                        "Atlass Plisse Premium",
                        "Atlass Plisse Barbie Blue",
                        "Atlass Plisse Barbie Black"
                );
        assertThat(response.replacementStart()).isZero();
        assertThat(response.replacementEnd()).isEqualTo(5);
    }

    @Test
    void atlasPReturnsOnlyCompleteWordsSharedByAllSuggestions() {
        ProductSuggestionResponse response = suggest("atlas p");

        assertThat(response.commonCompletion()).isEqualTo("Atlass Plisse");
        assertThat(response.suggestions()).hasSize(3);
    }

    @Test
    void atlasBNarrowsToBarbieAndAdditionalLettersDistinguishVariants() {
        ProductSuggestionResponse barbie = suggest("atlas b");
        ProductSuggestionResponse blue = suggest("atlas blu");
        ProductSuggestionResponse black = suggest("atlas bla");

        assertThat(barbie.suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly("Atlass Plisse Barbie Blue", "Atlass Plisse Barbie Black");
        assertThat(blue.suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly("Atlass Plisse Barbie Blue");
        assertThat(black.suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly("Atlass Plisse Barbie Black");
    }

    @Test
    void smallTyposMatchButUnrelatedTextDoesNot() {
        ProductSuggestionResponse typo = suggest("atlss p");
        ProductSuggestionResponse unrelated = suggest("zzzzzz");

        assertThat(typo.suggestions()).hasSize(3);
        assertThat(unrelated).isEqualTo(ProductSuggestionResponse.empty(6));
    }

    @Test
    void fuzzyThresholdsAndFirstCharacterRuleAreEnforced() {
        when(productRepository.findSuggestionCandidates(eq(USER_ID), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(
                        candidate(30L, "Atlass"),
                        candidate(31L, "Marshmallow"),
                        candidate(32L, "Tea")
                ));

        assertThat(suggest("atlaxs").suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly("Atlass");
        assertThat(suggest("atlxxs").suggestions()).isEmpty();
        assertThat(suggest("marshmalo").suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly("Marshmallow");
        assertThat(suggest("tee").suggestions()).isEmpty();
        assertThat(suggest("btlass").suggestions()).isEmpty();
    }

    @Test
    void fragmentsNeedAtLeastTwoNormalizedLetters() {
        assertThat(suggest("")).isEqualTo(ProductSuggestionResponse.empty(0));
        assertThat(suggest("a")).isEqualTo(ProductSuggestionResponse.empty(1));

        verifyNoInteractions(productRepository);
    }

    @Test
    void exactProductNameIsCompletedEvenWhenItPrefixesLongerName() {
        when(productRepository.findSuggestionCandidates(eq(USER_ID), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(candidate(10L, "Blue"), candidate(11L, "Blue Fish")));

        ProductSuggestionResponse response = suggest("Blue");

        assertThat(response).isEqualTo(ProductSuggestionResponse.empty(4));
    }

    @Test
    void matchingIsCaseInsensitiveDiacriticInsensitiveAndSupportsCyrillic() {
        when(productRepository.findSuggestionCandidates(eq(USER_ID), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(
                        candidate(20L, "Café Молоко"),
                        candidate(21L, "Éclair Ваниль")
                ));

        ProductSuggestionResponse mixed = suggest("CAFE мол");
        ProductSuggestionResponse accentedInitial = suggest("ecl");

        assertThat(mixed.suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly("Café Молоко");
        assertThat(accentedInitial.suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly("Éclair Ваниль");
        verify(productRepository).findSuggestionCandidates(
                eq(USER_ID),
                org.mockito.ArgumentMatchers.argThat(initials -> initials.contains("é")),
                any(Pageable.class)
        );
    }

    @Test
    void completedFirstProductDoesNotBlockUnfinishedSecondProductAcrossSeparators() {
        assertSecondFragmentSelected(
                "add 2 Atlass Plisse Barbie Blue, add 3 atlas p from Bishkek",
                "atlas p"
        );
        assertSecondFragmentSelected(
                "add 2 Atlass Plisse Barbie Blue and add 3 atlas p from Bishkek",
                "atlas p"
        );
        assertSecondFragmentSelected(
                "add 2 Atlass Plisse Barbie Blue\nadd 3 atlas p from Bishkek",
                "atlas p"
        );
    }

    @Test
    void cursorInsideMiddleProductReplacesOnlyThatProductFragment() {
        String text = "add 2 atlas blu at Bishkek and sell Cotton";
        int cursor = text.indexOf("blu") + "blu".length();

        ProductSuggestionResponse response = suggest(text, cursor);

        assertThat(response.replacementStart()).isEqualTo(text.indexOf("atlas"));
        assertThat(response.replacementEnd()).isEqualTo(text.indexOf("blu") + 3);
        assertThat(text.substring(response.replacementStart(), response.replacementEnd()))
                .isEqualTo("atlas blu");
        assertThat(response.suggestions())
                .extracting(suggestion -> suggestion.name())
                .containsExactly("Atlass Plisse Barbie Blue");
    }

    @Test
    void repeatedProductRemainsEligibleInANewFragment() {
        String text = "add Atlass Plisse Barbie Blue and add atlas blu";

        ProductSuggestionResponse response = suggest(text);

        assertThat(response.suggestions())
                .extracting(suggestion -> suggestion.productId())
                .containsExactly(2L);
        assertThat(text.substring(response.replacementStart(), response.replacementEnd()))
                .isEqualTo("atlas blu");
    }

    @Test
    void queryIsUserScopedAndCappedAtTwoHundredNames() {
        suggest("atlas p");

        verify(productRepository).findSuggestionCandidates(
                eq(USER_ID),
                anyCollection(),
                org.mockito.ArgumentMatchers.argThat(pageable -> pageable.getPageSize() == 200)
        );
    }

    private void assertSecondFragmentSelected(String text, String fragment) {
        int cursor = text.lastIndexOf(fragment) + fragment.length();
        ProductSuggestionResponse response = suggest(text, cursor);

        assertThat(text.substring(response.replacementStart(), response.replacementEnd()))
                .isEqualTo(fragment);
        assertThat(response.commonCompletion()).isEqualTo("Atlass Plisse");
    }

    private ProductSuggestionResponse suggest(String text) {
        return suggest(text, text.length());
    }

    private ProductSuggestionResponse suggest(String text, int cursor) {
        return service.suggest(
                new ProductSuggestionRequest(text, cursor, ProductSuggestionContext.COMMAND, 5),
                USER_ID
        );
    }

    private List<ProductNameProjection> atlassFamily() {
        return List.of(
                candidate(2L, "Atlass Plisse Barbie Blue"),
                candidate(3L, "Atlass Plisse Barbie Black"),
                candidate(4L, "Atlass Plisse Premium")
        );
    }

    private ProductNameProjection candidate(Long id, String name) {
        return new Candidate(id, name);
    }

    private record Candidate(Long id, String name) implements ProductNameProjection {
        @Override
        public Long getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
