package com.is.aigul.service;

import com.is.aigul.dto.LogbookPageResponse;
import com.is.aigul.model.Logbook;
import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import com.is.aigul.repository.LogbookRepository;
import com.is.aigul.repository.ProductLocationRepository;
import com.is.aigul.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import(LogbookService.class)
class LogbookPersistenceIntegrationTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    @Autowired
    private LogbookService logbookService;
    @Autowired
    private LogbookRepository logbookRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductLocationRepository productLocationRepository;
    @Autowired
    private EntityManager entityManager;

    private Product atlas;
    private ProductLocation warehouse;

    @BeforeEach
    void setUp() {
        atlas = product(USER_ID, "Atlas");
        warehouse = location(USER_ID, atlas, "Warehouse", 15);
    }

    @Test
    void pageUsesStableOrderCombinedFiltersAndTenantIsolation() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 7, 20, 12, 0);
        Logbook olderId = log(USER_ID, "SELL", timestamp, atlas, warehouse, "priority order");
        Logbook newerId = log(USER_ID, "SELL", timestamp, atlas, warehouse, "priority order");
        log(USER_ID, "ADD", timestamp.plusMinutes(1), atlas, warehouse, "priority order");

        Product foreignProduct = product(OTHER_USER_ID, "Foreign Atlas");
        ProductLocation foreignLocation = location(
                OTHER_USER_ID,
                foreignProduct,
                "Warehouse",
                99
        );
        log(
                OTHER_USER_ID,
                "SELL",
                timestamp.plusHours(1),
                foreignProduct,
                foreignLocation,
                "priority order"
        );

        LogbookPageResponse response = logbookService.search(
                USER_ID,
                0,
                20,
                "priority",
                "sell",
                timestamp.minusDays(1),
                timestamp.plusDays(1)
        );

        assertThat(response.getItems())
                .extracting("id")
                .containsExactly(newerId.getId(), olderId.getId());
        assertThat(response.getTotalItems()).isEqualTo(2);
        assertThat(response.isHasNext()).isFalse();
        assertThat(response.getItems())
                .allSatisfy(item -> {
                    assertThat(item.getProductName()).isEqualTo("Atlas");
                    assertThat(item.getLocationName()).isEqualTo("Warehouse");
                });
    }

    @Test
    void pageClampsSizeAndReturnsEmptyOutOfRangePage() {
        for (int index = 0; index < 105; index++) {
            log(
                    USER_ID,
                    "SELL",
                    LocalDateTime.of(2026, 7, 1, 0, 0).plusMinutes(index),
                    atlas,
                    warehouse,
                    null
            );
        }

        LogbookPageResponse first = logbookService.search(
                USER_ID, 0, 500, null, null, null, null
        );
        LogbookPageResponse empty = logbookService.search(
                USER_ID, 4, 20, "missing", null, null, null
        );

        assertThat(first.getSize()).isEqualTo(100);
        assertThat(first.getItems()).hasSize(100);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.isHasNext()).isTrue();
        assertThat(empty.getItems()).isEmpty();
        assertThat(empty.getTotalItems()).isZero();
        assertThat(empty.getTotalPages()).isZero();
    }

    @Test
    void pageAndLegacyReadsFetchRelationsWithBoundedQueries() {
        for (int index = 0; index < 10; index++) {
            log(
                    USER_ID,
                    "SELL",
                    LocalDateTime.of(2026, 7, 1, 0, 0).plusMinutes(index),
                    atlas,
                    warehouse,
                    null
            );
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        logbookService.search(USER_ID, 0, 20, null, null, null, null);
        long pageQueries = statistics.getPrepareStatementCount();
        statistics.clear();

        logbookService.getAll(USER_ID);
        long legacyQueries = statistics.getPrepareStatementCount();

        assertThat(pageQueries).isLessThanOrEqualTo(2);
        assertThat(legacyQueries).isEqualTo(1);
    }

    private Product product(Long userId, String name) {
        Product product = new Product();
        product.setUserId(userId);
        product.setName(name);
        product.setPrice(10.0);
        return productRepository.save(product);
    }

    private ProductLocation location(
            Long userId,
            Product product,
            String name,
            int quantity
    ) {
        ProductLocation location = new ProductLocation();
        location.setUserId(userId);
        location.setProduct(product);
        location.setLocationName(name);
        location.setQuantity(quantity);
        return productLocationRepository.save(location);
    }

    private Logbook log(
            Long userId,
            String action,
            LocalDateTime timestamp,
            Product product,
            ProductLocation location,
            String notes
    ) {
        Logbook log = Logbook.builder()
                .userId(userId)
                .actionType(action)
                .timestamp(timestamp)
                .quantity(2)
                .amount(20.0)
                .notes(notes)
                .product(product)
                .productLocation(location)
                .build();
        return logbookRepository.save(log);
    }
}
