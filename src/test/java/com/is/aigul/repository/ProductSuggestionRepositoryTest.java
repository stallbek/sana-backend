package com.is.aigul.repository;

import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductSuggestionRepositoryTest {

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductLocationRepository productLocationRepository;

    @Test
    void suggestionProjectionIsUserScopedDeterministicAndCapped() {
        List<Product> products = new ArrayList<>();
        for (int index = 0; index < 205; index++) {
            products.add(product(7L, "A Product %03d".formatted(index)));
        }
        products.add(product(8L, "A Other User Secret"));
        Product archived = product(7L, "A Product Archived");
        archived.setDeletedAt(LocalDateTime.now());
        products.add(archived);
        productRepository.saveAllAndFlush(products);

        List<ProductNameProjection> candidates = productRepository.findSuggestionCandidates(
                7L,
                List.of("a"),
                PageRequest.of(0, 200)
        );

        assertThat(candidates).hasSize(200);
        assertThat(candidates.get(0).getName()).isEqualTo("A Product 000");
        assertThat(candidates.get(199).getName()).isEqualTo("A Product 199");
        assertThat(candidates)
                .extracting(ProductNameProjection::getName)
                .doesNotContain("A Other User Secret", "A Product Archived");
        assertThat(candidates)
                .allSatisfy(candidate -> {
                    assertThat(candidate.getId()).isNotNull();
                    assertThat(candidate.getName()).startsWith("A Product");
                });
    }

    @Test
    void archivedProductsDisappearFromActiveLookupsNamesAndStockLocations() {
        Product archived = product(7L, "Atlas");
        archived.setDeletedAt(LocalDateTime.now());
        archived = productRepository.save(archived);
        Product active = productRepository.save(product(7L, "atlas"));

        ProductLocation activeLocation = location(active, "Bishkek", 3);
        ProductLocation archivedLocation = location(archived, "Archive", 50);
        productLocationRepository.saveAllAndFlush(List.of(activeLocation, archivedLocation));

        assertThat(productRepository.findByUserId(7L)).containsExactly(active);
        assertThat(productRepository.findByNameIgnoreCaseAndUserId("Atlas", 7L))
                .containsExactly(active);
        assertThat(productRepository.findByIdAndUserId(archived.getId(), 7L)).isEmpty();
        assertThat(productRepository.findAllNamesByUserId(7L)).containsExactly("atlas");
        assertThat(productLocationRepository.findByUserId(7L)).containsExactly(activeLocation);
        assertThat(productLocationRepository.findDistinctLocationNamesByUserId(7L))
                .containsExactly("Bishkek");
    }

    private Product product(Long userId, String name) {
        Product product = new Product();
        product.setUserId(userId);
        product.setName(name);
        product.setPrice(100.0);
        return product;
    }

    private ProductLocation location(Product product, String name, int quantity) {
        ProductLocation location = new ProductLocation();
        location.setUserId(product.getUserId());
        location.setProduct(product);
        location.setLocationName(name);
        location.setQuantity(quantity);
        return location;
    }
}
