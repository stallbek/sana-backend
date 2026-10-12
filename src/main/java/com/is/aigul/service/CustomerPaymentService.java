package com.is.aigul.service;

import com.is.aigul.dto.CustomerPaymentEntryResponse;
import com.is.aigul.dto.CustomerPaymentMutationResponse;
import com.is.aigul.dto.CustomerPaymentPageResponse;
import com.is.aigul.dto.CustomerPaymentRequest;
import com.is.aigul.dto.CustomerPaymentReversalRequest;
import com.is.aigul.dto.CustomerSummaryResponse;
import com.is.aigul.model.Customer;
import com.is.aigul.model.CustomerPaymentEntry;
import com.is.aigul.model.CustomerPaymentEntryType;
import com.is.aigul.model.Logbook;
import com.is.aigul.repository.CustomerPaymentEntryRepository;
import com.is.aigul.repository.CustomerRepository;
import com.is.aigul.repository.LogbookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerPaymentService {
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
    private static final Sort HISTORY_SORT = Sort.by(
            Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    private final CustomerPaymentEntryRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final LogbookRepository logbookRepository;

    public CustomerPaymentMutationResponse recordPayment(
            Long userId,
            Long customerId,
            String idempotencyKey,
            CustomerPaymentRequest request
    ) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        NormalizedPayment normalized = normalizePayment(request);
        String fingerprint = fingerprint(
                "PAYMENT|" + customerId + "|" + normalized.amount().toPlainString()
                        + "|" + normalized.method() + "|"
                        + (request.paidAt() == null ? "<server>" : normalized.occurredAt())
                        + "|" + nullSafe(normalized.note()));
        CustomerPaymentEntry replay = findReplay(userId, key, fingerprint);
        if (replay != null) {
            return mutationResponse(replay, userId, customerId);
        }

        Customer customer = lockOwnedCustomer(customerId, userId);
        replay = findReplay(userId, key, fingerprint);
        if (replay != null) {
            return mutationResponse(replay, userId, customerId);
        }
        CustomerSummaryResponse before = customerService.get(customerId, userId);
        BigDecimal totalOwed = BigDecimal.valueOf(before.getTotalOwed())
                .setScale(2, RoundingMode.HALF_UP);
        if (normalized.amount().compareTo(totalOwed) > 0) {
            throw new ResponseStatusException(
                    CONFLICT, "Payment cannot exceed the customer's total owed");
        }

        LocalDateTime now = LocalDateTime.now();
        CustomerPaymentEntry entry = new CustomerPaymentEntry();
        entry.setUserId(userId);
        entry.setCustomer(customer);
        entry.setType(CustomerPaymentEntryType.PAYMENT);
        entry.setAmount(normalized.amount());
        entry.setCurrency("som");
        entry.setMethod(normalized.method());
        entry.setOccurredAt(normalized.occurredAt());
        entry.setCreatedAt(now);
        entry.setNote(normalized.note());
        entry.setIdempotencyKey(key);
        entry.setRequestFingerprint(fingerprint);
        saveEntry(entry);
        log(entry, customer, normalized.amount().doubleValue(),
                "Payment recorded via " + normalized.method());
        return mutationResponse(entry, userId, customerId);
    }

    public CustomerPaymentMutationResponse reversePayment(
            Long userId,
            Long customerId,
            Long paymentId,
            String idempotencyKey,
            CustomerPaymentReversalRequest request
    ) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        String reason = normalizeRequiredText(
                request == null ? null : request.reason(), "Reversal reason");
        String fingerprint = fingerprint(
                "REVERSAL|" + customerId + "|" + paymentId + "|" + reason);
        CustomerPaymentEntry replay = findReplay(userId, key, fingerprint);
        if (replay != null) {
            return mutationResponse(replay, userId, customerId);
        }

        Customer customer = lockOwnedCustomer(customerId, userId);
        replay = findReplay(userId, key, fingerprint);
        if (replay != null) {
            return mutationResponse(replay, userId, customerId);
        }
        CustomerPaymentEntry payment = paymentRepository
                .findByIdAndUserIdAndCustomer_Id(paymentId, userId, customerId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Payment not found"));
        if (payment.getType() != CustomerPaymentEntryType.PAYMENT) {
            throw new ResponseStatusException(CONFLICT, "Only payment entries can be reversed");
        }
        if (paymentRepository.existsByReversalOfPayment_Id(paymentId)) {
            throw new ResponseStatusException(CONFLICT, "Payment is already reversed");
        }

        LocalDateTime now = LocalDateTime.now();
        CustomerPaymentEntry reversal = new CustomerPaymentEntry();
        reversal.setUserId(userId);
        reversal.setCustomer(customer);
        reversal.setType(CustomerPaymentEntryType.REVERSAL);
        reversal.setAmount(payment.getAmount());
        reversal.setCurrency("som");
        reversal.setOccurredAt(now);
        reversal.setCreatedAt(now);
        reversal.setReversalReason(reason);
        reversal.setReversalOfPayment(payment);
        reversal.setIdempotencyKey(key);
        reversal.setRequestFingerprint(fingerprint);
        saveEntry(reversal);
        log(reversal, customer, reversal.getAmount().negate().doubleValue(), "Payment reversed");
        return mutationResponse(reversal, userId, customerId);
    }

    @Transactional(readOnly = true)
    public CustomerPaymentPageResponse history(
            Long userId, Long customerId, int page, int size) {
        if (!customerRepository.findByIdAndUserId(customerId, userId).isPresent()) {
            throw new ResponseStatusException(NOT_FOUND, "Customer not found");
        }
        int normalizedSize = size <= 0 ? 20 : Math.min(size, 100);
        Page<CustomerPaymentEntry> result = paymentRepository.findByUserIdAndCustomer_Id(
                userId, customerId,
                PageRequest.of(Math.max(page, 0), normalizedSize, HISTORY_SORT));
        return new CustomerPaymentPageResponse(
                result.getContent().stream().map(CustomerPaymentEntryResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    private CustomerPaymentMutationResponse mutationResponse(
            CustomerPaymentEntry entry, Long userId, Long customerId) {
        return new CustomerPaymentMutationResponse(
                CustomerPaymentEntryResponse.from(entry),
                customerService.get(customerId, userId));
    }

    private CustomerPaymentEntry findReplay(Long userId, String key, String fingerprint) {
        return paymentRepository.findByUserIdAndIdempotencyKey(userId, key)
                .map(existing -> {
                    if (!existing.getRequestFingerprint().equals(fingerprint)) {
                        throw new ResponseStatusException(
                                CONFLICT, "Idempotency-Key was already used for a different request");
                    }
                    return existing;
                })
                .orElse(null);
    }

    private void saveEntry(CustomerPaymentEntry entry) {
        try {
            paymentRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    CONFLICT, "Payment request conflicts with an existing ledger entry", exception);
        }
    }

    private Customer lockOwnedCustomer(Long customerId, Long userId) {
        return customerRepository.findOwnedForUpdate(customerId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Customer not found"));
    }

    private NormalizedPayment normalizePayment(CustomerPaymentRequest request) {
        if (request == null || request.amount() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Payment amount is required");
        }
        if (request.amount().scale() > 2) {
            throw new ResponseStatusException(
                    BAD_REQUEST, "Payment amount must have at most two decimal places");
        }
        BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Payment amount must be positive");
        }
        if (amount.precision() > 19) {
            throw new ResponseStatusException(
                    BAD_REQUEST, "Payment amount is too large");
        }
        if (request.method() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Payment method is required");
        }
        LocalDateTime occurredAt = request.paidAt() == null
                ? LocalDateTime.now() : request.paidAt();
        if (occurredAt.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(BAD_REQUEST, "paidAt cannot be in the future");
        }
        return new NormalizedPayment(
                amount, request.method(), occurredAt, normalizeOptionalText(request.note(), "Note"));
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Idempotency-Key is required");
        }
        String key = value.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ResponseStatusException(
                    BAD_REQUEST, "Idempotency-Key must be at most 255 characters");
        }
        return key;
    }

    private String normalizeRequiredText(String value, String field) {
        String normalized = normalizeOptionalText(value, field);
        if (normalized == null) {
            throw new ResponseStatusException(BAD_REQUEST, field + " is required");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(
                    BAD_REQUEST, field + " must be at most 500 characters");
        }
        return normalized;
    }

    private void log(
            CustomerPaymentEntry entry, Customer customer, double amount, String notes) {
        Logbook log = Logbook.builder()
                .userId(entry.getUserId())
                .actionType(entry.getType() == CustomerPaymentEntryType.PAYMENT
                        ? "PAYMENT" : "PAYMENT_REVERSED")
                .timestamp(LocalDateTime.now())
                .quantity(0)
                .amount(amount)
                .notes(notes)
                .customerId(customer.getId())
                .customerName(customer.getName())
                .paymentEntryId(entry.getId())
                .currency("som")
                .build();
        logbookRepository.save(log);
    }

    private String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record NormalizedPayment(
            BigDecimal amount,
            com.is.aigul.model.CustomerPaymentMethod method,
            LocalDateTime occurredAt,
            String note
    ) {
    }
}
