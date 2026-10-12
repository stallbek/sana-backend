package com.is.aigul.controller;

import com.is.aigul.dto.CustomerPageResponse;
import com.is.aigul.dto.CustomerRequest;
import com.is.aigul.dto.CustomerSummaryResponse;
import com.is.aigul.dto.CustomerPaymentMutationResponse;
import com.is.aigul.dto.CustomerPaymentPageResponse;
import com.is.aigul.dto.CustomerPaymentRequest;
import com.is.aigul.dto.CustomerPaymentReversalRequest;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.CustomerPaymentService;
import com.is.aigul.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final AuthService authService;
    private final CustomerPaymentService customerPaymentService;

    @GetMapping
    public CustomerPageResponse listCustomers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestHeader("token") String token
    ) {
        return customerService.search(requireUser(token), page, size, q);
    }

    @PostMapping
    public CustomerSummaryResponse createCustomer(
            @RequestBody CustomerRequest request,
            @RequestHeader("token") String token
    ) {
        return customerService.create(requireUser(token), request);
    }

    @PutMapping("/{id}")
    public CustomerSummaryResponse updateCustomer(
            @PathVariable Long id,
            @RequestBody CustomerRequest request,
            @RequestHeader("token") String token
    ) {
        return customerService.update(id, requireUser(token), request);
    }

    @GetMapping("/{id}")
    public CustomerSummaryResponse getCustomer(
            @PathVariable Long id,
            @RequestHeader("token") String token
    ) {
        return customerService.get(id, requireUser(token));
    }

    @PostMapping("/{customerId}/payments")
    public ResponseEntity<CustomerPaymentMutationResponse> recordPayment(
            @PathVariable Long customerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CustomerPaymentRequest request,
            @RequestHeader("token") String token
    ) {
        return ResponseEntity.status(201).body(customerPaymentService.recordPayment(
                requireUser(token), customerId, idempotencyKey, request));
    }

    @PostMapping("/{customerId}/payments/{paymentId}/reversal")
    public CustomerPaymentMutationResponse reversePayment(
            @PathVariable Long customerId,
            @PathVariable Long paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CustomerPaymentReversalRequest request,
            @RequestHeader("token") String token
    ) {
        return customerPaymentService.reversePayment(
                requireUser(token), customerId, paymentId, idempotencyKey, request);
    }

    @GetMapping("/{customerId}/payments")
    public CustomerPaymentPageResponse listPayments(
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("token") String token
    ) {
        return customerPaymentService.history(requireUser(token), customerId, page, size);
    }

    private Long requireUser(String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Unauthorized");
        }
        return userId;
    }
}
