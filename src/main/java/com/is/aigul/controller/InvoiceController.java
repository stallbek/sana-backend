package com.is.aigul.controller;

import com.is.aigul.dto.InvoiceDetailResponse;
import com.is.aigul.dto.InvoiceCustomerRequest;
import com.is.aigul.dto.InvoicePageResponse;
import com.is.aigul.dto.InvoiceSearchRequest;
import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final AuthService authService;

    @GetMapping
    public InvoicePageResponse listInvoices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) Long customerId,
            @RequestHeader("token") String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");

        InvoiceSearchRequest request = new InvoiceSearchRequest(
                parseOperation(operation),
                parseStatus(status),
                product,
                q,
                start,
                end,
                customerId
        );

        try {
            return invoiceService.search(userId, request, page, size, sort);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public InvoiceDetailResponse getInvoice(@PathVariable Long id, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return invoiceService.getDetail(id, userId);
    }

    @PutMapping("/{id}/customer")
    public InvoiceDetailResponse assignCustomer(
            @PathVariable Long id,
            @RequestBody InvoiceCustomerRequest request,
            @RequestHeader("token") String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return invoiceService.assignCustomer(id, request == null ? null : request.customerId(), userId);
    }

    @DeleteMapping("/{id}/customer")
    public InvoiceDetailResponse deassignCustomer(@PathVariable Long id, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return invoiceService.deassignCustomer(id, userId);
    }

    private InvoiceOperation parseOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return null;
        }
        try {
            return InvoiceOperation.valueOf(operation.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "operation must be ADD or SELL");
        }
    }

    private InvoiceStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return InvoiceStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "status must be ACTIVE or VOIDED");
        }
    }
}
