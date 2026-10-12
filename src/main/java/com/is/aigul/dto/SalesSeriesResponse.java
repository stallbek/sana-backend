package com.is.aigul.dto;

import java.time.LocalDate;
import java.util.List;

public record SalesSeriesResponse(
        SalesSeriesPeriod period,
        List<Bucket> buckets
) {
    public record Bucket(LocalDate start, double value) {
    }
}
