package com.is.aigul.controller;

import com.is.aigul.dto.ActionResponse;
import com.is.aigul.dto.ProductSuggestionItem;
import com.is.aigul.dto.ProductSuggestionRequest;
import com.is.aigul.dto.ProductSuggestionResponse;
import com.is.aigul.exception.ProductNotFoundException;
import com.is.aigul.repository.ProductNameProjection;
import com.is.aigul.repository.ProductRepository;
import com.is.aigul.service.ActionService;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.ProductService;
import com.is.aigul.service.ProductSuggestionRequestValidator;
import com.is.aigul.service.ProductSuggestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ProductControllerTest {

    private AuthService authService;
    private ActionService actionService;
    private ProductSuggestionService suggestionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        actionService = mock(ActionService.class);
        suggestionService = mock(ProductSuggestionService.class);
        ProductController controller = new ProductController(
                mock(ProductService.class),
                authService,
                suggestionService,
                new ProductSuggestionRequestValidator(),
                actionService
        );
        mockMvc = standaloneSetup(controller).build();
    }

    @Test
    void authenticatedRequestUsesCurrentUserAndSerializesContract() throws Exception {
        when(authService.getUserId("valid-token")).thenReturn(7L);
        when(suggestionService.suggest(any(ProductSuggestionRequest.class), eq(7L)))
                .thenReturn(new ProductSuggestionResponse(
                        4,
                        11,
                        "Atlass Plisse",
                        List.of(new ProductSuggestionItem(2L, "Atlass Plisse Barbie Blue"))
                ));

        mockMvc.perform(get("/api/products/suggestions")
                        .header("token", "valid-token")
                        .param("text", "add atlas p")
                        .param("cursor", "11")
                        .param("context", "command"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacementStart").value(4))
                .andExpect(jsonPath("$.replacementEnd").value(11))
                .andExpect(jsonPath("$.commonCompletion").value("Atlass Plisse"))
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].productId").value(2))
                .andExpect(jsonPath("$.suggestions[0].name").value("Atlass Plisse Barbie Blue"));

        verify(suggestionService).suggest(
                org.mockito.ArgumentMatchers.argThat(request -> request.limit() == 5),
                eq(7L)
        );
    }

    @Test
    void missingAndInvalidAuthenticationReturnUnauthorizedBeforeValidation() throws Exception {
        when(authService.getUserId(nullable(String.class))).thenReturn(null);

        mockMvc.perform(get("/api/products/suggestions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/products/suggestions")
                        .header("token", "bad-token")
                        .param("text", "atlas")
                        .param("cursor", "5")
                        .param("context", "COMMAND"))
                .andExpect(status().isUnauthorized());

        verify(suggestionService, never()).suggest(any(), any());
    }

    @Test
    void invalidCursorLimitContextAndOversizedTextReturnBadRequest() throws Exception {
        when(authService.getUserId("valid-token")).thenReturn(7L);

        assertBadRequest("atlas", "-1", "COMMAND", "5");
        assertBadRequest("atlas", "6", "COMMAND", "5");
        assertBadRequest("atlas", "5", "COMMAND", "0");
        assertBadRequest("atlas", "5", "COMMAND", "11");
        assertBadRequest("atlas", "5", "SEARCH", "5");
        assertBadRequest("atlas", "5", "OTHER", "5");
        assertBadRequest("x".repeat(501), "0", "COMMAND", "5");

        verify(suggestionService, never()).suggest(any(), any());
    }

    @Test
    void missingRequiredParametersAndMalformedNumbersReturnBadRequest() throws Exception {
        when(authService.getUserId("valid-token")).thenReturn(7L);

        mockMvc.perform(get("/api/products/suggestions")
                        .header("token", "valid-token"))
                .andExpect(status().isBadRequest());
        assertBadRequest("atlas", "cursor", "COMMAND", "5");
        assertBadRequest("atlas", "5", "COMMAND", "many");

        verify(suggestionService, never()).suggest(any(), any());
    }

    @Test
    void emptyAndNoMatchResponsesUseCursorSentinelAndExposeNoOtherUserProducts() throws Exception {
        when(authService.getUserId("valid-token")).thenReturn(7L);
        when(suggestionService.suggest(any(ProductSuggestionRequest.class), eq(7L)))
                .thenAnswer(invocation -> {
                    ProductSuggestionRequest request = invocation.getArgument(0);
                    return ProductSuggestionResponse.empty(request.cursor());
                });

        mockMvc.perform(get("/api/products/suggestions")
                        .header("token", "valid-token")
                        .param("text", "")
                        .param("cursor", "0")
                        .param("context", "COMMAND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacementStart").value(0))
                .andExpect(jsonPath("$.replacementEnd").value(0))
                .andExpect(jsonPath("$.commonCompletion").doesNotExist())
                .andExpect(jsonPath("$.suggestions").isEmpty());

        mockMvc.perform(get("/api/products/suggestions")
                        .header("token", "valid-token")
                        .param("text", "zzzzzz")
                        .param("cursor", "6")
                        .param("context", "COMMAND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions").isEmpty())
                .andExpect(jsonPath("$.suggestions[?(@.name == 'Other User Secret')]").isEmpty());
    }

    @Test
    void authenticatedResponseCannotIncludeAnotherUsersRepositoryCandidates() throws Exception {
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductSuggestionService realSuggestionService = new ProductSuggestionService(productRepository);
        ProductController controller = new ProductController(
                mock(ProductService.class),
                authService,
                realSuggestionService,
                new ProductSuggestionRequestValidator(),
                actionService
        );
        MockMvc isolatedMockMvc = standaloneSetup(controller).build();

        when(authService.getUserId("user-seven-token")).thenReturn(7L);
        when(productRepository.findSuggestionCandidates(eq(7L), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(new Candidate(2L, "Atlass Plisse Barbie Blue")));
        when(productRepository.findSuggestionCandidates(eq(8L), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(new Candidate(99L, "Atlass Other User Secret")));

        isolatedMockMvc.perform(get("/api/products/suggestions")
                        .header("token", "user-seven-token")
                        .param("text", "atlas blu")
                        .param("cursor", "9")
                        .param("context", "COMMAND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].productId").value(2))
                .andExpect(jsonPath("$.suggestions[0].name").value("Atlass Plisse Barbie Blue"))
                .andExpect(jsonPath("$.suggestions[?(@.productId == 99)]").isEmpty());

        verify(productRepository).findSuggestionCandidates(eq(7L), anyCollection(), any(Pageable.class));
        verify(productRepository, never()).findSuggestionCandidates(eq(8L), anyCollection(), any(Pageable.class));
    }

    @Test
    void authenticatedDeleteUsesCurrentUserAndSerializesActionResponse() throws Exception {
        when(authService.getUserId("valid-token")).thenReturn(7L);
        when(actionService.delete(42L, 7L)).thenReturn(new ActionResponse(
                "PRODUCT_DELETED",
                "Product deleted successfully",
                Map.of("productId", 42L, "productName", "Atlas")
        ));

        mockMvc.perform(delete("/api/products/42")
                        .header("token", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PRODUCT_DELETED"))
                .andExpect(jsonPath("$.data.productId").value(42))
                .andExpect(jsonPath("$.data.productName").value("Atlas"));

        verify(actionService).delete(42L, 7L);
    }

    @Test
    void deleteRejectsMissingAndInvalidAuthenticationBeforeServiceCall() throws Exception {
        when(authService.getUserId(nullable(String.class))).thenReturn(null);

        mockMvc.perform(delete("/api/products/42"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/products/42")
                        .header("token", "bad-token"))
                .andExpect(status().isUnauthorized());

        verify(actionService, never()).delete(any(Long.class), any(Long.class));
    }

    @Test
    void deleteReturnsNotFoundForMissingOwnedArchivedAndOtherUserProducts() throws Exception {
        when(authService.getUserId("valid-token")).thenReturn(7L);
        when(actionService.delete(40L, 7L)).thenThrow(new ProductNotFoundException());
        when(actionService.delete(41L, 7L)).thenThrow(new ProductNotFoundException());
        when(actionService.delete(42L, 7L)).thenThrow(new ProductNotFoundException());

        mockMvc.perform(delete("/api/products/40").header("token", "valid-token"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/products/41").header("token", "valid-token"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/products/42").header("token", "valid-token"))
                .andExpect(status().isNotFound());
    }

    private void assertBadRequest(String text, String cursor, String context, String limit) throws Exception {
        mockMvc.perform(get("/api/products/suggestions")
                        .header("token", "valid-token")
                        .param("text", text)
                        .param("cursor", cursor)
                        .param("context", context)
                        .param("limit", limit))
                .andExpect(status().isBadRequest());
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
