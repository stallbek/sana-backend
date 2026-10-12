package com.is.aigul.controller;

import com.is.aigul.dto.InvoicePageResponse;
import com.is.aigul.dto.InvoiceSearchRequest;
import com.is.aigul.dto.InvoiceCustomerRequest;
import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvoiceControllerTest {

    @Test
    void listInvoicesPassesAuthUserAndFiltersToService() {
        InvoiceService invoiceService = mock(InvoiceService.class);
        AuthService authService = mock(AuthService.class);
        InvoiceController controller = new InvoiceController(invoiceService, authService);
        InvoicePageResponse expected = new InvoicePageResponse(List.of(), 0, 20, 0, 0, false);

        when(authService.getUserId("token")).thenReturn(7L);
        when(invoiceService.search(eq(7L), any(InvoiceSearchRequest.class), eq(0), eq(20), eq("createdAt,desc")))
                .thenReturn(expected);

        InvoicePageResponse response = controller.listInvoices(
                0,
                20,
                "createdAt,desc",
                "SELL",
                "ACTIVE",
                "atlas",
                "bishkek",
                null,
                null,
                123L,
                "token"
        );

        assertThat(response).isSameAs(expected);
        verify(invoiceService).search(eq(7L), argThat(request ->
                request.getOperation() == InvoiceOperation.SELL
                        && request.getStatus() == InvoiceStatus.ACTIVE
                        && "atlas".equals(request.getProduct())
                        && "bishkek".equals(request.getQ())
                        && Long.valueOf(123L).equals(request.getCustomerId())
        ), eq(0), eq(20), eq("createdAt,desc"));
    }

    @Test
    void invalidOperationFilterReturnsBadRequestWithoutServiceCall() {
        InvoiceService invoiceService = mock(InvoiceService.class);
        AuthService authService = mock(AuthService.class);
        InvoiceController controller = new InvoiceController(invoiceService, authService);
        when(authService.getUserId("token")).thenReturn(7L);

        assertThatThrownBy(() -> controller.listInvoices(
                0,
                20,
                "createdAt,desc",
                "MOVE",
                null,
                null,
                null,
                null,
                null,
                null,
                "token"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("operation must be ADD or SELL");

        verifyNoInteractions(invoiceService);
    }

    @Test
    void assignmentEndpointsPassOwnedInvoiceAndCustomerToService() {
        InvoiceService invoiceService = mock(InvoiceService.class);
        AuthService authService = mock(AuthService.class);
        InvoiceController controller = new InvoiceController(invoiceService, authService);
        when(authService.getUserId("token")).thenReturn(7L);

        controller.assignCustomer(44L, new InvoiceCustomerRequest(9L), "token");
        controller.deassignCustomer(44L, "token");

        verify(invoiceService).assignCustomer(44L, 9L, 7L);
        verify(invoiceService).deassignCustomer(44L, 7L);
    }
}
