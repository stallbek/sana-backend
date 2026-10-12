package com.is.aigul.service;

import com.is.aigul.dto.InvoiceDetailResponse;
import com.is.aigul.dto.InvoicePageResponse;
import com.is.aigul.dto.InvoiceSearchRequest;
import com.is.aigul.dto.InvoiceSummaryResponse;
import com.is.aigul.model.Customer;
import com.is.aigul.model.Invoice;
import com.is.aigul.model.InvoiceItem;
import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import com.is.aigul.repository.CustomerRepository;
import com.is.aigul.repository.InvoiceRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
@RequiredArgsConstructor
@Transactional
public class InvoiceService {

    private static final DateTimeFormatter INVOICE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String RANDOM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Set<String> ALLOWED_SORTS = Set.of(
            "createdAt",
            "invoiceNumber",
            "operation",
            "status",
            "totalAmount",
            "productSummary"
    );

    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final SecureRandom random = new SecureRandom();

    public Invoice saveInvoice(Long userId, InvoiceOperation operation, Double totalAmount, List<InvoiceLineSnapshot> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Cannot save invoice without line items");
        }

        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(generateInvoiceNumber());
        invoice.setUserId(userId);
        invoice.setOperation(operation);
        invoice.setStatus(InvoiceStatus.ACTIVE);
        invoice.setCreatedAt(LocalDateTime.now());
        invoice.setTotalAmount(totalAmount);
        invoice.setCurrency("som");
        invoice.setItemCount(lines.size());
        invoice.setProductSummary(productSummary(lines));

        for (InvoiceLineSnapshot line : lines) {
            InvoiceItem item = new InvoiceItem();
            item.setProductId(line.getProductId());
            item.setProductName(line.getProductName());
            item.setLocationId(line.getLocationId());
            item.setLocationName(line.getLocationName());
            item.setQuantity(line.getQuantity());
            item.setUnit(line.getUnit());
            item.setUnitPrice(line.getUnitPrice());
            item.setDiscount(line.getDiscount());
            item.setSubtotal(line.getSubtotal());
            item.setStockBefore(line.getStockBefore());
            item.setStockAfter(line.getStockAfter());
            invoice.addItem(item);
        }

        return invoiceRepository.save(invoice);
    }

    @Transactional(readOnly = true)
    public InvoicePageResponse search(Long userId, InvoiceSearchRequest request, int page, int size, String sort) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size), parseSort(sort));
        Page<Invoice> invoices = invoiceRepository.findAll(specification(userId, request), pageable);
        return new InvoicePageResponse(
                invoices.getContent().stream().map(InvoiceSummaryResponse::from).toList(),
                invoices.getNumber(),
                invoices.getSize(),
                invoices.getTotalElements(),
                invoices.getTotalPages(),
                invoices.hasNext()
        );
    }

    @Transactional(readOnly = true)
    public InvoiceDetailResponse getDetail(Long id, Long userId) {
        Invoice invoice = invoiceRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Invoice not found"));
        invoice.getItems().size();
        return InvoiceDetailResponse.from(invoice);
    }

    public InvoiceDetailResponse assignCustomer(Long invoiceId, Long customerId, Long userId) {
        if (customerId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "customerId is required");
        }
        Invoice invoice = findOwnedForUpdate(invoiceId, userId);
        validateAssignmentEligibility(invoice);
        List<Long> customerIds = new ArrayList<>();
        customerIds.add(customerId);
        if (invoice.getCustomer() != null) {
            customerIds.add(invoice.getCustomer().getId());
        }
        List<Long> orderedIds = customerIds.stream().distinct().sorted().toList();
        List<Customer> lockedCustomers = customerRepository.findOwnedForUpdate(userId, orderedIds);
        Customer customer = lockedCustomers.stream()
                .filter(candidate -> customerId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Customer not found"));

        if (invoice.getCustomer() == null || !customer.getId().equals(invoice.getCustomer().getId())) {
            invoice.setCustomer(customer);
            invoice.setAssignedAt(LocalDateTime.now());
            invoiceRepository.save(invoice);
        }
        invoice.getItems().size();
        return InvoiceDetailResponse.from(invoice);
    }

    public InvoiceDetailResponse deassignCustomer(Long invoiceId, Long userId) {
        Invoice invoice = findOwnedForUpdate(invoiceId, userId);
        validateDeassignmentEligibility(invoice);
        if (invoice.getCustomer() != null) {
            customerRepository.findOwnedForUpdate(
                    userId, List.of(invoice.getCustomer().getId()));
        }
        if (invoice.getCustomer() != null || invoice.getAssignedAt() != null) {
            invoice.setCustomer(null);
            invoice.setAssignedAt(null);
            invoiceRepository.save(invoice);
        }
        invoice.getItems().size();
        return InvoiceDetailResponse.from(invoice);
    }

    public Invoice validateUndoInvoice(Long invoiceId, Long userId, InvoiceOperation operation) {
        Invoice invoice = invoiceRepository.findOwnedForUpdate(invoiceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));

        if (invoice.getOperation() != operation) {
            throw new IllegalArgumentException("Invoice operation does not match undo command");
        }
        if (invoice.getStatus() != InvoiceStatus.ACTIVE) {
            throw new IllegalArgumentException("Invoice is already voided");
        }

        return invoice;
    }

    public void voidInvoice(Invoice invoice) {
        Invoice lockedInvoice = findOwnedForUpdate(invoice.getId(), invoice.getUserId());
        if (lockedInvoice.getCustomer() != null) {
            customerRepository.findOwnedForUpdate(
                    lockedInvoice.getUserId(), List.of(lockedInvoice.getCustomer().getId()));
        }
        lockedInvoice.setCustomer(null);
        lockedInvoice.setAssignedAt(null);
        lockedInvoice.setStatus(InvoiceStatus.VOIDED);
        lockedInvoice.setVoidedAt(LocalDateTime.now());
        invoiceRepository.save(lockedInvoice);
    }

    private Specification<Invoice> specification(Long userId, InvoiceSearchRequest request) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));

            if (request.getOperation() != null) {
                predicates.add(cb.equal(root.get("operation"), request.getOperation()));
            }
            if (request.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), request.getStatus()));
            }
            if (request.getStart() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), request.getStart()));
            }
            if (request.getEnd() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), request.getEnd()));
            }
            if (request.getCustomerId() != null) {
                predicates.add(cb.equal(root.get("customer").get("id"), request.getCustomerId()));
            }
            if (hasText(request.getProduct()) || hasText(request.getQ())) {
                Join<Invoice, InvoiceItem> item = root.join("items", JoinType.LEFT);
                if (hasText(request.getProduct())) {
                    predicates.add(cb.like(cb.lower(item.get("productName")), contains(request.getProduct())));
                }
                if (hasText(request.getQ())) {
                    String q = contains(request.getQ());
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("invoiceNumber")), q),
                            cb.like(cb.lower(root.get("productSummary")), q),
                            cb.like(cb.lower(item.get("productName")), q),
                            cb.like(cb.lower(item.get("locationName")), q)
                    ));
                }
                query.distinct(true);
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Invoice findOwnedForUpdate(Long invoiceId, Long userId) {
        return invoiceRepository.findOwnedForUpdate(invoiceId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Invoice not found"));
    }

    private void validateAssignmentEligibility(Invoice invoice) {
        validateDeassignmentEligibility(invoice);
        if (invoice.getTotalAmount() == null
                || !Double.isFinite(invoice.getTotalAmount())
                || invoice.getTotalAmount() <= 0.0) {
            throw new ResponseStatusException(CONFLICT, "Only SELL invoices with a positive total can be assigned");
        }
    }

    private void validateDeassignmentEligibility(Invoice invoice) {
        if (invoice.getOperation() != InvoiceOperation.SELL) {
            throw new ResponseStatusException(CONFLICT, "Only SELL invoices can have a customer");
        }
        if (invoice.getStatus() != InvoiceStatus.ACTIVE) {
            throw new ResponseStatusException(CONFLICT, "Only active invoices can have a customer");
        }
    }

    private Sort parseSort(String rawSort) {
        String sort = hasText(rawSort) ? rawSort.trim() : "createdAt,desc";
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!ALLOWED_SORTS.contains(property)) {
            throw new IllegalArgumentException("Unsupported invoice sort: " + property);
        }

        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())) {
            direction = Sort.Direction.ASC;
        }
        return Sort.by(direction, property);
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private String generateInvoiceNumber() {
        String date = LocalDateTime.now().format(INVOICE_DATE);
        String invoiceNumber;
        do {
            invoiceNumber = "INV-" + date + "-" + randomSuffix();
        } while (invoiceRepository.existsByInvoiceNumber(invoiceNumber));
        return invoiceNumber;
    }

    private String randomSuffix() {
        StringBuilder suffix = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            suffix.append(RANDOM_CHARS.charAt(random.nextInt(RANDOM_CHARS.length())));
        }
        return suffix.toString();
    }

    private String productSummary(List<InvoiceLineSnapshot> lines) {
        String first = lines.get(0).getProductName();
        if (lines.size() == 1) {
            return first;
        }
        return first + " +" + (lines.size() - 1) + " more";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String contains(String value) {
        return "%" + value.toLowerCase(Locale.ROOT).trim() + "%";
    }

    @Value
    public static class InvoiceLineSnapshot {
        Long productId;
        String productName;
        Long locationId;
        String locationName;
        int quantity;
        String unit;
        Double unitPrice;
        Double discount;
        Double subtotal;
        int stockBefore;
        int stockAfter;
    }
}
