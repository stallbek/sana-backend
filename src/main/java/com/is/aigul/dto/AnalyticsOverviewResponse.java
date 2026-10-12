package com.is.aigul.dto;

import java.util.List;

public record AnalyticsOverviewResponse(
        Summary summary,
        Growth growth,
        List<ProductPerformance> topProducts,
        List<ProductPerformance> leastSellingProducts,
        List<DeadStockProduct> deadStockProducts
) {
    public record Summary(
            long totalProducts,
            long totalItems,
            double totalValue,
            double recentSales
    ) {
    }

    public record Growth(
            double yesterday,
            double lastWeek,
            double lastMonth
    ) {
    }

    public record ProductPerformance(
            String name,
            long count,
            double revenue,
            double turnover
    ) {
    }

    public record DeadStockProduct(
            String name,
            long stock,
            double value
    ) {
    }
}
