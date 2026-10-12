package com.is.aigul.service;

import com.is.aigul.dto.CustomerPageResponse;
import com.is.aigul.dto.CustomerRequest;
import com.is.aigul.dto.CustomerSummaryResponse;
import com.is.aigul.model.Customer;
import com.is.aigul.repository.CustomerDebtSummary;
import com.is.aigul.repository.CustomerPaymentBalanceSummary;
import com.is.aigul.repository.CustomerPaymentEntryRepository;
import com.is.aigul.repository.CustomerRepository;
import com.is.aigul.repository.InvoiceRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_PHONE_LENGTH = 32;

    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerPaymentEntryRepository paymentEntryRepository;

    @Transactional(readOnly = true)
    public CustomerPageResponse search(Long userId, int page, int size, String q) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                normalizeSize(size),
                Sort.by(Sort.Direction.ASC, "name").and(Sort.by(Sort.Direction.ASC, "id"))
        );
        Page<Customer> customers = customerRepository.findAll(specification(userId, q), pageable);
        Map<Long, DebtTotals> totalsByCustomer = loadTotals(
                userId,
                customers.getContent().stream().map(Customer::getId).toList()
        );

        return new CustomerPageResponse(
                customers.getContent().stream()
                        .map(customer -> toResponse(customer, totalsByCustomer.get(customer.getId())))
                        .toList(),
                customers.getNumber(),
                customers.getSize(),
                customers.getTotalElements(),
                customers.getTotalPages(),
                customers.hasNext()
        );
    }

    public CustomerSummaryResponse create(Long userId, CustomerRequest request) {
        Customer customer = new Customer();
        customer.setUserId(userId);
        applyRequest(customer, request);
        LocalDateTime now = LocalDateTime.now();
        customer.setCreatedAt(now);
        customer.setUpdatedAt(now);
        return CustomerSummaryResponse.from(customerRepository.save(customer), 0.0, 0.0, 0);
    }

    public CustomerSummaryResponse update(Long id, Long userId, CustomerRequest request) {
        Customer customer = findOwned(id, userId);
        applyRequest(customer, request);
        customer.setUpdatedAt(LocalDateTime.now());
        Customer saved = customerRepository.save(customer);
        return toResponse(saved, loadTotals(userId, List.of(saved.getId())).get(saved.getId()));
    }

    @Transactional(readOnly = true)
    public CustomerSummaryResponse get(Long id, Long userId) {
        Customer customer = findOwned(id, userId);
        return toResponse(customer, loadTotals(userId, List.of(customer.getId())).get(customer.getId()));
    }

    private Customer findOwned(Long id, Long userId) {
        return customerRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Customer not found"));
    }

    private void applyRequest(Customer customer, CustomerRequest request) {
        if (request == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Customer request is required");
        }
        customer.setName(normalizeName(request.name()));
        customer.setPhone(normalizePhone(request.phone()));
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Customer name is required");
        }
        String name = value.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "Customer name must be at most 120 characters");
        }
        return name;
    }

    private String normalizePhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String digits = digitsOnly(trimmed);
        if (digits.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Customer phone must contain at least one digit");
        }
        String phone = trimmed.startsWith("+") ? "+" + digits : digits;
        if (phone.length() > MAX_PHONE_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "Customer phone must be at most 32 characters after normalization");
        }
        return phone;
    }

    private Specification<Customer> specification(Long userId, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (q != null && !q.isBlank()) {
                String trimmed = q.trim();
                String nameQuery = "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
                String phoneQuery = digitsOnly(trimmed);
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), nameQuery);
                if (phoneQuery.isEmpty()) {
                    predicates.add(nameMatch);
                } else {
                    predicates.add(cb.or(nameMatch, cb.like(root.get("phone"), "%" + phoneQuery + "%")));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Map<Long, DebtTotals> loadTotals(Long userId, List<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, DebtTotals> totals = new HashMap<>();
        for (CustomerDebtSummary summary : invoiceRepository.summarizeActiveSellAssignments(userId, customerIds)) {
            totals.put(
                    summary.getCustomerId(),
                    new DebtTotals(summary.getTotalOwed(), 0.0, summary.getAssignedInvoiceCount())
            );
        }
        for (CustomerPaymentBalanceSummary summary
                : paymentEntryRepository.summarizeNetPayments(userId, customerIds)) {
            totals.compute(
                    summary.getCustomerId(),
                    (ignored, current) -> new DebtTotals(
                            current == null ? 0.0 : current.grossAssignedAmount(),
                            summary.getNetPaidAmount() == null
                                    ? 0.0 : summary.getNetPaidAmount().doubleValue(),
                            current == null ? 0 : current.assignedInvoiceCount()
                    )
            );
        }
        return totals;
    }

    private CustomerSummaryResponse toResponse(Customer customer, DebtTotals totals) {
        if (totals == null) {
            return CustomerSummaryResponse.from(customer, 0.0, 0.0, 0);
        }
        return CustomerSummaryResponse.from(
                customer,
                totals.grossAssignedAmount(),
                totals.netPaidAmount(),
                totals.assignedInvoiceCount()
        );
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String digitsOnly(String value) {
        StringBuilder digits = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isDigit(character)) {
                digits.append(character);
            }
        }
        return digits.toString();
    }

    private record DebtTotals(
            Double grossAssignedAmount,
            Double netPaidAmount,
            long assignedInvoiceCount
    ) {
    }
}
