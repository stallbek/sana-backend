package com.is.aigul.service;

import com.is.aigul.dto.AnalyticsOverviewResponse;
import com.is.aigul.dto.SalesSeriesPeriod;
import com.is.aigul.dto.SalesSeriesResponse;
import com.is.aigul.repository.AnalyticsQueryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class BusinessAnalyticsService {

    private final AnalyticsQueryRepository analyticsQueryRepository;
    private final Clock clock;

    @Autowired
    public BusinessAnalyticsService(AnalyticsQueryRepository analyticsQueryRepository) {
        this(analyticsQueryRepository, Clock.systemDefaultZone());
    }

    BusinessAnalyticsService(AnalyticsQueryRepository analyticsQueryRepository, Clock clock) {
        this.analyticsQueryRepository = analyticsQueryRepository;
        this.clock = clock;
    }

    public AnalyticsOverviewResponse overview(
            Long userId,
            LocalDateTime start,
            LocalDateTime end
    ) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("start must be before or equal to end");
        }

        List<AnalyticsQueryRepository.InventoryRow> inventory =
                analyticsQueryRepository.findInventory(userId);
        List<AnalyticsQueryRepository.ProductSalesRow> sales =
                analyticsQueryRepository.findProductSales(userId, start, end);

        Map<Long, AnalyticsQueryRepository.ProductSalesRow> salesByProduct = new HashMap<>();
        for (AnalyticsQueryRepository.ProductSalesRow sale : sales) {
            salesByProduct.put(sale.productId(), sale);
        }
        Map<Long, Long> stockByProduct = new HashMap<>();
        for (AnalyticsQueryRepository.InventoryRow item : inventory) {
            stockByProduct.put(item.productId(), item.stock());
        }

        List<AnalyticsOverviewResponse.ProductPerformance> topProducts = sales.stream()
                .map(sale -> productPerformance(
                        sale,
                        stockByProduct.getOrDefault(sale.productId(), 0L)
                ))
                .sorted(Comparator
                        .comparingLong(AnalyticsOverviewResponse.ProductPerformance::count)
                        .reversed()
                        .thenComparing(
                                AnalyticsOverviewResponse.ProductPerformance::name,
                                String.CASE_INSENSITIVE_ORDER
                        ))
                .toList();

        List<AnalyticsOverviewResponse.ProductPerformance> leastSellingProducts = inventory.stream()
                .map(item -> {
                    AnalyticsQueryRepository.ProductSalesRow sale = salesByProduct.getOrDefault(
                            item.productId(),
                            new AnalyticsQueryRepository.ProductSalesRow(
                                    item.productId(),
                                    item.name(),
                                    0,
                                    0
                            )
                    );
                    return productPerformance(sale, item.stock());
                })
                .sorted(Comparator
                        .comparingLong(AnalyticsOverviewResponse.ProductPerformance::count)
                        .thenComparing(
                                AnalyticsOverviewResponse.ProductPerformance::name,
                                String.CASE_INSENSITIVE_ORDER
                        ))
                .toList();

        LocalDateTime now = LocalDateTime.now(clock);
        Set<Long> recentlySoldProductIds =
                analyticsQueryRepository.findProductsSoldSince(userId, now.minusDays(30));
        List<AnalyticsOverviewResponse.DeadStockProduct> deadStockProducts = inventory.stream()
                .filter(item -> item.stock() > 0)
                .filter(item -> !recentlySoldProductIds.contains(item.productId()))
                .map(item -> new AnalyticsOverviewResponse.DeadStockProduct(
                        item.name(),
                        item.stock(),
                        item.stock() * item.price()
                ))
                .sorted(Comparator
                        .comparingDouble(AnalyticsOverviewResponse.DeadStockProduct::value)
                        .reversed()
                        .thenComparing(
                                AnalyticsOverviewResponse.DeadStockProduct::name,
                                String.CASE_INSENSITIVE_ORDER
                        ))
                .toList();

        long totalItems = inventory.stream()
                .mapToLong(AnalyticsQueryRepository.InventoryRow::stock)
                .sum();
        double totalValue = inventory.stream()
                .mapToDouble(item -> item.stock() * item.price())
                .sum();

        return new AnalyticsOverviewResponse(
                new AnalyticsOverviewResponse.Summary(
                        inventory.size(),
                        totalItems,
                        totalValue,
                        analyticsQueryRepository.sumSales(userId, start, end)
                ),
                growth(userId, now.toLocalDate()),
                topProducts,
                leastSellingProducts,
                deadStockProducts
        );
    }

    public SalesSeriesResponse salesSeries(Long userId, SalesSeriesPeriod period) {
        LocalDate today = LocalDate.now(clock);
        LocalDate firstBucket;
        LocalDate endExclusive;
        int bucketCount;
        String unit;

        switch (period) {
            case DAILY -> {
                firstBucket = today.minusDays(6);
                endExclusive = today.plusDays(1);
                bucketCount = 7;
                unit = "day";
            }
            case WEEKLY -> {
                LocalDate currentWeek = today.with(
                        TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
                );
                firstBucket = currentWeek.minusWeeks(3);
                endExclusive = currentWeek.plusWeeks(1);
                bucketCount = 4;
                unit = "week";
            }
            case MONTHLY -> {
                LocalDate currentMonth = today.withDayOfMonth(1);
                firstBucket = currentMonth.minusMonths(5);
                endExclusive = currentMonth.plusMonths(1);
                bucketCount = 6;
                unit = "month";
            }
            default -> throw new IllegalArgumentException("Unsupported sales series period");
        }

        Map<LocalDate, Double> values = analyticsQueryRepository.findSalesBuckets(
                userId,
                firstBucket.atStartOfDay(),
                endExclusive.atStartOfDay(),
                unit
        );

        List<SalesSeriesResponse.Bucket> buckets = new ArrayList<>(bucketCount);
        LocalDate bucket = firstBucket;
        for (int index = 0; index < bucketCount; index++) {
            buckets.add(new SalesSeriesResponse.Bucket(bucket, values.getOrDefault(bucket, 0.0)));
            bucket = switch (period) {
                case DAILY -> bucket.plusDays(1);
                case WEEKLY -> bucket.plusWeeks(1);
                case MONTHLY -> bucket.plusMonths(1);
            };
        }
        return new SalesSeriesResponse(period, buckets);
    }

    private AnalyticsOverviewResponse.ProductPerformance productPerformance(
            AnalyticsQueryRepository.ProductSalesRow sale,
            long currentStock
    ) {
        long totalFlow = currentStock + sale.sold();
        double turnover = totalFlow > 0 ? sale.sold() * 100.0 / totalFlow : 0;
        return new AnalyticsOverviewResponse.ProductPerformance(
                sale.name(),
                sale.sold(),
                sale.revenue(),
                turnover
        );
    }

    private AnalyticsOverviewResponse.Growth growth(Long userId, LocalDate today) {
        LocalDate thisMonth = today.withDayOfMonth(1);
        LocalDate lastMonth = thisMonth.minusMonths(1);
        LocalDate twoMonthsAgo = thisMonth.minusMonths(2);
        AnalyticsQueryRepository.GrowthSalesRow sales =
                analyticsQueryRepository.findGrowthSales(
                        userId,
                        today.atStartOfDay(),
                        today.plusDays(1).atStartOfDay(),
                        today.minusDays(1).atStartOfDay(),
                        today.minusDays(7).atStartOfDay(),
                        today.minusDays(6).atStartOfDay(),
                        today.minusDays(14).atStartOfDay(),
                        today.minusDays(13).atStartOfDay(),
                        lastMonth.atStartOfDay(),
                        thisMonth.atStartOfDay(),
                        twoMonthsAgo.atStartOfDay()
                );
        return new AnalyticsOverviewResponse.Growth(
                growthPercent(sales.today(), sales.yesterday()),
                growthPercent(sales.week(), sales.priorWeek()),
                growthPercent(sales.month(), sales.priorMonth())
        );
    }

    private double growthPercent(double current, double previous) {
        if (previous == 0) {
            return current > 0 ? 100 : 0;
        }
        return (current - previous) * 100 / previous;
    }
}
