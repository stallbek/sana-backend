package com.is.aigul.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class AnalyticsQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<InventoryRow> findInventory(Long userId) {
        return jdbcTemplate.query("""
                SELECT p.id,
                       p.name,
                       COALESCE(p.price, 0) AS price,
                       COALESCE(SUM(pl.quantity), 0) AS stock
                FROM product p
                LEFT JOIN product_location pl
                  ON pl.product_id = p.id
                 AND pl.user_id = :userId
                WHERE p.user_id = :userId
                  AND p.deleted_at IS NULL
                GROUP BY p.id, p.name, p.price
                ORDER BY lower(p.name), p.id
                """, Map.of("userId", userId), (resultSet, rowNumber) -> new InventoryRow(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getDouble("price"),
                resultSet.getLong("stock")
        ));
    }

    public List<ProductSalesRow> findProductSales(
            Long userId,
            LocalDateTime start,
            LocalDateTime end
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT l.product_id,
                       p.name,
                       COALESCE(SUM(l.quantity), 0) AS sold,
                       COALESCE(SUM(COALESCE(l.amount, 0)), 0) AS revenue
                FROM logbook l
                JOIN product p
                  ON p.id = l.product_id
                 AND p.user_id = :userId
                WHERE l.user_id = :userId
                  AND l.action_type = 'SELL'
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource("userId", userId);
        appendRange(sql, parameters, start, end);
        sql.append(" GROUP BY l.product_id, p.name");

        return jdbcTemplate.query(sql.toString(), parameters, (resultSet, rowNumber) ->
                new ProductSalesRow(
                        resultSet.getLong("product_id"),
                        resultSet.getString("name"),
                        resultSet.getLong("sold"),
                        resultSet.getDouble("revenue")
                )
        );
    }

    public double sumSales(Long userId, LocalDateTime start, LocalDateTime end) {
        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(COALESCE(amount, 0)), 0)
                FROM logbook
                WHERE user_id = :userId
                  AND action_type = 'SELL'
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource("userId", userId);
        appendRange(sql, parameters, start, end);
        Number value = jdbcTemplate.queryForObject(sql.toString(), parameters, Number.class);
        return value == null ? 0 : value.doubleValue();
    }

    public Set<Long> findProductsSoldSince(Long userId, LocalDateTime since) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT product_id
                FROM logbook
                WHERE user_id = :userId
                  AND action_type = 'SELL'
                  AND timestamp >= :since
                  AND product_id IS NOT NULL
                """, Map.of("userId", userId, "since", since), Long.class)
                .stream()
                .collect(Collectors.toSet());
    }

    public GrowthSalesRow findGrowthSales(
            Long userId,
            LocalDateTime todayStart,
            LocalDateTime tomorrowStart,
            LocalDateTime yesterdayStart,
            LocalDateTime weekComparisonStart,
            LocalDateTime weekComparisonEnd,
            LocalDateTime priorWeekComparisonStart,
            LocalDateTime priorWeekComparisonEnd,
            LocalDateTime lastMonthStart,
            LocalDateTime thisMonthStart,
            LocalDateTime twoMonthsAgoStart
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("todayStart", todayStart)
                .addValue("tomorrowStart", tomorrowStart)
                .addValue("yesterdayStart", yesterdayStart)
                .addValue("weekStart", weekComparisonStart)
                .addValue("weekEnd", weekComparisonEnd)
                .addValue("priorWeekStart", priorWeekComparisonStart)
                .addValue("priorWeekEnd", priorWeekComparisonEnd)
                .addValue("lastMonthStart", lastMonthStart)
                .addValue("thisMonthStart", thisMonthStart)
                .addValue("twoMonthsAgoStart", twoMonthsAgoStart);

        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE
                           WHEN timestamp >= :todayStart AND timestamp < :tomorrowStart
                           THEN COALESCE(amount, 0) ELSE 0 END), 0) AS today_sales,
                       COALESCE(SUM(CASE
                           WHEN timestamp >= :yesterdayStart AND timestamp < :todayStart
                           THEN COALESCE(amount, 0) ELSE 0 END), 0) AS yesterday_sales,
                       COALESCE(SUM(CASE
                           WHEN timestamp >= :weekStart AND timestamp < :weekEnd
                           THEN COALESCE(amount, 0) ELSE 0 END), 0) AS week_sales,
                       COALESCE(SUM(CASE
                           WHEN timestamp >= :priorWeekStart AND timestamp < :priorWeekEnd
                           THEN COALESCE(amount, 0) ELSE 0 END), 0) AS prior_week_sales,
                       COALESCE(SUM(CASE
                           WHEN timestamp >= :lastMonthStart AND timestamp < :thisMonthStart
                           THEN COALESCE(amount, 0) ELSE 0 END), 0) AS month_sales,
                       COALESCE(SUM(CASE
                           WHEN timestamp >= :twoMonthsAgoStart AND timestamp < :lastMonthStart
                           THEN COALESCE(amount, 0) ELSE 0 END), 0) AS prior_month_sales
                FROM logbook
                WHERE user_id = :userId
                  AND action_type = 'SELL'
                  AND timestamp >= :twoMonthsAgoStart
                  AND timestamp < :tomorrowStart
                """, parameters, (resultSet, rowNumber) -> new GrowthSalesRow(
                resultSet.getDouble("today_sales"),
                resultSet.getDouble("yesterday_sales"),
                resultSet.getDouble("week_sales"),
                resultSet.getDouble("prior_week_sales"),
                resultSet.getDouble("month_sales"),
                resultSet.getDouble("prior_month_sales")
        ));
    }

    public Map<LocalDate, Double> findSalesBuckets(
            Long userId,
            LocalDateTime start,
            LocalDateTime end,
            String dateTruncUnit
    ) {
        if (!Set.of("day", "week", "month").contains(dateTruncUnit)) {
            throw new IllegalArgumentException("Unsupported sales bucket unit");
        }
        String sql = """
                SELECT date_trunc('%s', timestamp) AS bucket_start,
                       COALESCE(SUM(COALESCE(amount, 0)), 0) AS value
                FROM logbook
                WHERE user_id = :userId
                  AND action_type = 'SELL'
                  AND timestamp >= :start
                  AND timestamp < :end
                GROUP BY bucket_start
                ORDER BY bucket_start
                """.formatted(dateTruncUnit);

        return jdbcTemplate.query(
                sql,
                Map.of("userId", userId, "start", start, "end", end),
                resultSet -> {
                    java.util.LinkedHashMap<LocalDate, Double> buckets = new java.util.LinkedHashMap<>();
                    while (resultSet.next()) {
                        Timestamp timestamp = resultSet.getTimestamp("bucket_start");
                        buckets.put(timestamp.toLocalDateTime().toLocalDate(), resultSet.getDouble("value"));
                    }
                    return buckets;
                }
        );
    }

    private void appendRange(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            LocalDateTime start,
            LocalDateTime end
    ) {
        if (start != null) {
            sql.append(" AND timestamp >= :start");
            parameters.addValue("start", start);
        }
        if (end != null) {
            sql.append(" AND timestamp <= :end");
            parameters.addValue("end", end);
        }
    }

    public record InventoryRow(long productId, String name, double price, long stock) {
    }

    public record ProductSalesRow(long productId, String name, long sold, double revenue) {
    }

    public record GrowthSalesRow(
            double today,
            double yesterday,
            double week,
            double priorWeek,
            double month,
            double priorMonth
    ) {
    }
}
