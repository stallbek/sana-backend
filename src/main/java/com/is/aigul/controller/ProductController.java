package com.is.aigul.controller;

import com.is.aigul.dto.ActionResponse;
import com.is.aigul.dto.ProductDTO;
import com.is.aigul.dto.ProductSuggestionRequest;
import com.is.aigul.dto.ProductSuggestionResponse;
import com.is.aigul.service.ActionService;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.ProductService;
import com.is.aigul.service.ProductSuggestionRequestValidator;
import com.is.aigul.service.ProductSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;


@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final AuthService authService;
    private final ProductSuggestionService productSuggestionService;
    private final ProductSuggestionRequestValidator productSuggestionRequestValidator;
    private final ActionService actionService;

    @GetMapping
    public List<ProductDTO> getProducts(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) Integer minQty,
            @RequestParam(required = false) Integer maxQty,
            @RequestHeader("token") String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return productService.filter(name, location, minPrice, maxPrice, minQty, maxQty, userId);
    }

    @GetMapping("/suggestions")
    public ProductSuggestionResponse getSuggestions(
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String context,
            @RequestParam(required = false) String limit,
            @RequestHeader(value = "token", required = false) String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Unauthorized");
        }

        ProductSuggestionRequest request = productSuggestionRequestValidator.validate(
                text,
                cursor,
                context,
                limit
        );
        return productSuggestionService.suggest(request, userId);
    }

    @DeleteMapping("/{productId}")
    public ActionResponse deleteProduct(
            @PathVariable Long productId,
            @RequestHeader(value = "token", required = false) String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Unauthorized");
        }
        return actionService.delete(productId, userId);
    }
}
