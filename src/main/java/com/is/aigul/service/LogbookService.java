package com.is.aigul.service;

import com.is.aigul.model.Logbook;
import com.is.aigul.dto.LogbookDTO;
import com.is.aigul.dto.LogbookPageResponse;
import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import com.is.aigul.repository.LogbookRepository;
import com.is.aigul.repository.UserRepository;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class LogbookService {

    private final LogbookRepository logbookRepository;
    private final UserRepository userRepository;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort LOG_SORT = Sort.by(
            Sort.Order.desc("timestamp"),
            Sort.Order.desc("id")
    );

    public void logAction(String type, String user, ProductLocation productLocation,
                          int quantity, Double amount, String notes, Product product, Long userId) {

        String username = userRepository.findById(userId)
                .map(u -> {
                    if (u.getName() != null && !u.getName().isBlank()) {
                        return u.getName();
                    }
                    if (u.getUsername() != null && !u.getUsername().isBlank()) {
                        return u.getUsername();
                    }
                    return "User";
                })
                .orElse("Unknown");

        Logbook log = Logbook.builder()
                .actionType(type)
                .timestamp(LocalDateTime.now())
                .productLocation(productLocation)
                .quantity(quantity)
                .amount(amount)
                .notes("by " + username + ": " + notes)
                .product(product)
                .userId(userId)
                .build();

        logbookRepository.save(log);
    }

    public LogbookDTO toDTO(Logbook log) {
        return new LogbookDTO(
                log.getId(),
                log.getActionType(),
                log.getTimestamp(),
                log.getQuantity(),
                log.getAmount(),
                log.getNotes(),
                log.getProduct() != null ? log.getProduct().getName() : null,
                log.getProductLocation() != null ? log.getProductLocation().getLocationName() : null,
                log.getCustomerId(),
                log.getCustomerName(),
                log.getPaymentEntryId(),
                log.getCurrency()
        );
    }

    public List<LogbookDTO> getAll(Long userId) {
        return logbookRepository.findByUserIdOrderByTimestampDescIdDesc(userId)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    public List<LogbookDTO> getByActionType(String actionType, Long userId) {
        return logbookRepository
                .findByActionTypeIgnoreCaseAndUserIdOrderByTimestampDescIdDesc(actionType, userId)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    public List<LogbookDTO> getByProduct(String productName, Long userId) {
        return logbookRepository
                .findByProductLocation_Product_NameIgnoreCaseAndUserIdOrderByTimestampDescIdDesc(productName, userId)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    public List<LogbookDTO> getByLocation(String locationName, Long userId) {
        return logbookRepository
                .findByProductLocation_LocationNameIgnoreCaseAndUserIdOrderByTimestampDescIdDesc(locationName, userId)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    public List<LogbookDTO> getByDateRange(LocalDateTime start, LocalDateTime end, Long userId) {
        return logbookRepository
                .findByTimestampBetweenAndUserIdOrderByTimestampDescIdDesc(start, end, userId)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    public LogbookPageResponse search(
            Long userId,
            int page,
            int size,
            String query,
            String actionType,
            LocalDateTime start,
            LocalDateTime end
    ) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("start must be before or equal to end");
        }

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = size <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(size, MAX_PAGE_SIZE);

        Specification<Logbook> specification =
                (root, criteriaQuery, builder) -> builder.equal(root.get("userId"), userId);

        if (query != null && !query.isBlank()) {
            String pattern = "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
            specification = specification.and((root, criteriaQuery, builder) -> {
                var product = root.join("product", JoinType.LEFT);
                var location = root.join("productLocation", JoinType.LEFT);
                return builder.or(
                        builder.like(builder.lower(product.get("name")), pattern, '\\'),
                        builder.like(builder.lower(location.get("locationName")), pattern, '\\'),
                        builder.like(builder.lower(root.get("notes")), pattern, '\\')
                );
            });
        }

        if (actionType != null && !actionType.isBlank()) {
            String normalizedActionType = actionType.trim().toUpperCase(Locale.ROOT);
            specification = specification.and((root, criteriaQuery, builder) ->
                    builder.equal(root.get("actionType"), normalizedActionType)
            );
        }
        if (start != null) {
            specification = specification.and((root, criteriaQuery, builder) ->
                    builder.greaterThanOrEqualTo(root.get("timestamp"), start)
            );
        }
        if (end != null) {
            specification = specification.and((root, criteriaQuery, builder) ->
                    builder.lessThanOrEqualTo(root.get("timestamp"), end)
            );
        }

        Page<Logbook> result = logbookRepository.findAll(
                specification,
                PageRequest.of(normalizedPage, normalizedSize, LOG_SORT)
        );

        return new LogbookPageResponse(
                result.getContent().stream().map(this::toDTO).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext()
        );
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

}
