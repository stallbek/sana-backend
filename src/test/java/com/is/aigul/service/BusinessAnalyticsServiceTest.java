package com.is.aigul.service;

import com.is.aigul.dto.AnalyticsOverviewResponse;
import com.is.aigul.dto.SalesSeriesPeriod;
import com.is.aigul.dto.SalesSeriesResponse;
import com.is.aigul.repository.AnalyticsQueryRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessAnalyticsServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-23T10:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void overviewPreservesSummaryRankingDeadStockAndTurnoverRules() {
        AnalyticsQueryRepository repository = mock(AnalyticsQueryRepository.class);
        BusinessAnalyticsService service = new BusinessAnalyticsService(repository, CLOCK);
        when(repository.findInventory(7L)).thenReturn(List.of(
                new AnalyticsQueryRepository.InventoryRow(1L, "Atlas", 10, 8),
                new AnalyticsQueryRepository.InventoryRow(2L, "Bolt", 5, 4),
                new AnalyticsQueryRepository.InventoryRow(3L, "Cable", 3, 0)
        ));
        when(repository.findProductSales(eq(7L), any(), any())).thenReturn(List.of(
                new AnalyticsQueryRepository.ProductSalesRow(1L, "Atlas", 2, 20),
                new AnalyticsQueryRepository.ProductSalesRow(2L, "Bolt", 5, 25)
        ));
        when(repository.sumSales(eq(7L), any(), any())).thenReturn(45.0);
        when(repository.findProductsSoldSince(eq(7L), any())).thenReturn(Set.of(2L));
        when(repository.findGrowthSales(
                eq(7L), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(new AnalyticsQueryRepository.GrowthSalesRow(30, 0, 20, 10, 0, 0));

        AnalyticsOverviewResponse response = service.overview(
                7L,
                LocalDate.of(2026, 7, 1).atStartOfDay(),
                LocalDate.of(2026, 7, 31).atTime(23, 59)
        );

        assertThat(response.summary().totalProducts()).isEqualTo(3);
        assertThat(response.summary().totalItems()).isEqualTo(12);
        assertThat(response.summary().totalValue()).isEqualTo(100);
        assertThat(response.summary().recentSales()).isEqualTo(45);
        assertThat(response.topProducts())
                .extracting(AnalyticsOverviewResponse.ProductPerformance::name)
                .containsExactly("Bolt", "Atlas");
        assertThat(response.topProducts().get(0).turnover()).isEqualTo(5 * 100.0 / 9);
        assertThat(response.leastSellingProducts())
                .extracting(AnalyticsOverviewResponse.ProductPerformance::name)
                .containsExactly("Cable", "Atlas", "Bolt");
        assertThat(response.deadStockProducts())
                .containsExactly(new AnalyticsOverviewResponse.DeadStockProduct("Atlas", 8, 80));
        assertThat(response.growth().yesterday()).isEqualTo(100);
        assertThat(response.growth().lastWeek()).isEqualTo(100);
        assertThat(response.growth().lastMonth()).isZero();
    }

    @Test
    void emptyOverviewReturnsZeroesAndEmptyCollections() {
        AnalyticsQueryRepository repository = mock(AnalyticsQueryRepository.class);
        BusinessAnalyticsService service = new BusinessAnalyticsService(repository, CLOCK);
        when(repository.findInventory(7L)).thenReturn(List.of());
        when(repository.findProductSales(7L, null, null)).thenReturn(List.of());
        when(repository.findProductsSoldSince(eq(7L), any())).thenReturn(Set.of());
        when(repository.findGrowthSales(
                eq(7L), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(new AnalyticsQueryRepository.GrowthSalesRow(0, 0, 0, 0, 0, 0));

        AnalyticsOverviewResponse response = service.overview(7L, null, null);

        assertThat(response.summary())
                .isEqualTo(new AnalyticsOverviewResponse.Summary(0, 0, 0, 0));
        assertThat(response.topProducts()).isEmpty();
        assertThat(response.leastSellingProducts()).isEmpty();
        assertThat(response.deadStockProducts()).isEmpty();
    }

    @Test
    void salesSeriesZeroFillsEveryExpectedBucket() {
        AnalyticsQueryRepository repository = mock(AnalyticsQueryRepository.class);
        BusinessAnalyticsService service = new BusinessAnalyticsService(repository, CLOCK);
        when(repository.findSalesBuckets(eq(7L), any(), any(), eq("day"))).thenReturn(Map.of(
                LocalDate.of(2026, 7, 20), 15.0,
                LocalDate.of(2026, 7, 23), 30.0
        ));

        SalesSeriesResponse response = service.salesSeries(7L, SalesSeriesPeriod.DAILY);

        assertThat(response.buckets()).hasSize(7);
        assertThat(response.buckets().get(0))
                .isEqualTo(new SalesSeriesResponse.Bucket(LocalDate.of(2026, 7, 17), 0));
        assertThat(response.buckets().get(3))
                .isEqualTo(new SalesSeriesResponse.Bucket(LocalDate.of(2026, 7, 20), 15));
        assertThat(response.buckets().get(6))
                .isEqualTo(new SalesSeriesResponse.Bucket(LocalDate.of(2026, 7, 23), 30));
    }
}
