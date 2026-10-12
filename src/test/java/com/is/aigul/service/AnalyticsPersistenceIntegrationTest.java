package com.is.aigul.service;

import com.is.aigul.dto.AnalyticsOverviewResponse;
import com.is.aigul.model.Logbook;
import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import com.is.aigul.repository.AnalyticsQueryRepository;
import com.is.aigul.repository.LogbookRepository;
import com.is.aigul.repository.ProductLocationRepository;
import com.is.aigul.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Import({AnalyticsQueryRepository.class, BusinessAnalyticsService.class})
class AnalyticsPersistenceIntegrationTest {

    @Autowired
    private BusinessAnalyticsService analyticsService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductLocationRepository productLocationRepository;
    @Autowired
    private LogbookRepository logbookRepository;

    @Test
    void overviewAppliesDateFilterAndExcludesOtherTenantData() {
        LocalDateTime now = LocalDateTime.now();
        Product atlas = product(7L, "Atlas", 10);
        Product bolt = product(7L, "Bolt", 5);
        Product foreign = product(8L, "Foreign", 100);
        ProductLocation atlasStock = location(7L, atlas, 8);
        location(7L, bolt, 4);
        ProductLocation foreignStock = location(8L, foreign, 100);

        log(7L, atlas, atlasStock, now.minusDays(1), 2, 20.0);
        log(7L, atlas, atlasStock, now.minusDays(60), 3, 30.0);
        log(8L, foreign, foreignStock, now.minusHours(1), 9, 900.0);

        AnalyticsOverviewResponse response = analyticsService.overview(
                7L,
                now.minusDays(2),
                now
        );

        assertThat(response.summary())
                .isEqualTo(new AnalyticsOverviewResponse.Summary(2, 12, 100, 20));
        assertThat(response.topProducts())
                .extracting(AnalyticsOverviewResponse.ProductPerformance::name)
                .containsExactly("Atlas");
        assertThat(response.leastSellingProducts())
                .extracting(AnalyticsOverviewResponse.ProductPerformance::name)
                .containsExactly("Bolt", "Atlas");
        assertThat(response.deadStockProducts())
                .containsExactly(new AnalyticsOverviewResponse.DeadStockProduct("Bolt", 4, 20));
    }

    @Test
    void overviewExcludesArchivedInventoryButKeepsItsHistoricalSales() {
        LocalDateTime now = LocalDateTime.now();
        Product active = product(7L, "Active Atlas", 10);
        Product archived = product(7L, "Archived Atlas", 20);
        ProductLocation activeStock = location(7L, active, 8);
        ProductLocation archivedStock = location(7L, archived, 50);
        log(7L, active, activeStock, now.minusHours(2), 2, 20.0);
        log(7L, archived, archivedStock, now.minusHours(1), 3, 60.0);
        archived.setDeletedAt(now);
        productRepository.saveAndFlush(archived);

        AnalyticsOverviewResponse response = analyticsService.overview(7L, null, null);

        assertThat(response.summary())
                .isEqualTo(new AnalyticsOverviewResponse.Summary(1, 8, 80, 80));
        assertThat(response.topProducts())
                .extracting(AnalyticsOverviewResponse.ProductPerformance::name)
                .containsExactly("Archived Atlas", "Active Atlas");
        assertThat(response.leastSellingProducts())
                .extracting(AnalyticsOverviewResponse.ProductPerformance::name)
                .containsExactly("Active Atlas");
        assertThat(response.deadStockProducts()).isEmpty();
    }

    private Product product(Long userId, String name, double price) {
        Product product = new Product();
        product.setUserId(userId);
        product.setName(name);
        product.setPrice(price);
        return productRepository.save(product);
    }

    private ProductLocation location(Long userId, Product product, int quantity) {
        ProductLocation location = new ProductLocation();
        location.setUserId(userId);
        location.setProduct(product);
        location.setLocationName("Warehouse " + product.getName());
        location.setQuantity(quantity);
        return productLocationRepository.save(location);
    }

    private void log(
            Long userId,
            Product product,
            ProductLocation location,
            LocalDateTime timestamp,
            int quantity,
            Double amount
    ) {
        logbookRepository.save(Logbook.builder()
                .userId(userId)
                .actionType("SELL")
                .timestamp(timestamp)
                .quantity(quantity)
                .amount(amount)
                .product(product)
                .productLocation(location)
                .build());
    }
}
