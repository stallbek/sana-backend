package com.is.aigul.controller;

import com.is.aigul.dto.CustomerPageResponse;
import com.is.aigul.dto.CustomerRequest;
import com.is.aigul.dto.CustomerSummaryResponse;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.CustomerService;
import com.is.aigul.service.CustomerPaymentService;
import com.is.aigul.dto.CustomerPaymentRequest;
import com.is.aigul.dto.CustomerPaymentReversalRequest;
import com.is.aigul.model.CustomerPaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomerControllerTest {

    @Test
    void customerEndpointsPassAuthenticatedMerchantToService() {
        CustomerService customerService = mock(CustomerService.class);
        AuthService authService = mock(AuthService.class);
        CustomerPaymentService paymentService = mock(CustomerPaymentService.class);
        CustomerController controller = new CustomerController(
                customerService, authService, paymentService);
        CustomerRequest request = new CustomerRequest("Aizada", "+996555123456");
        when(authService.getUserId("token")).thenReturn(7L);

        controller.listCustomers(0, 20, "aiz", "token");
        controller.createCustomer(request, "token");
        controller.updateCustomer(9L, request, "token");
        controller.getCustomer(9L, "token");
        CustomerPaymentRequest paymentRequest = new CustomerPaymentRequest(
                new BigDecimal("10.00"), CustomerPaymentMethod.CASH, null, null);
        CustomerPaymentReversalRequest reversalRequest =
                new CustomerPaymentReversalRequest("mistake");
        var createPaymentResponse =
                controller.recordPayment(9L, "pay-1", paymentRequest, "token");
        controller.reversePayment(9L, 41L, "reverse-1", reversalRequest, "token");
        controller.listPayments(9L, 0, 20, "token");

        verify(customerService).search(7L, 0, 20, "aiz");
        verify(customerService).create(7L, request);
        verify(customerService).update(9L, 7L, request);
        verify(customerService).get(9L, 7L);
        verify(paymentService).recordPayment(7L, 9L, "pay-1", paymentRequest);
        verify(paymentService).reversePayment(7L, 9L, 41L, "reverse-1", reversalRequest);
        verify(paymentService).history(7L, 9L, 0, 20);
        org.assertj.core.api.Assertions.assertThat(createPaymentResponse.getStatusCode().value())
                .isEqualTo(201);
    }

    @Test
    void customerEndpointsRejectUnknownTokenBeforeServiceCall() {
        CustomerService customerService = mock(CustomerService.class);
        AuthService authService = mock(AuthService.class);
        CustomerController controller = new CustomerController(
                customerService, authService, mock(CustomerPaymentService.class));
        when(authService.getUserId("bad-token")).thenReturn(null);

        assertThatThrownBy(() -> controller.listCustomers(0, 20, null, "bad-token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
        verifyNoInteractions(customerService);
    }
}
