package com.is.aigul.service;

import com.is.aigul.dto.InvoiceDetailResponse;
import com.is.aigul.dto.InvoicePageResponse;
import com.is.aigul.dto.InvoiceSearchRequest;
import com.is.aigul.model.Customer;
import com.is.aigul.model.Invoice;
import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import com.is.aigul.repository.CustomerRepository;
import com.is.aigul.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private CustomerRepository customerRepository;

    private InvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        invoiceService = new InvoiceService(invoiceRepository, customerRepository);
    }

    @Test
    void saveSellInvoicePersistsImmutableLineSnapshots() {
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Invoice invoice = invoiceService.saveInvoice(
                USER_ID,
                InvoiceOperation.SELL,
                490.0,
                List.of(line(10L, "Atlas", 20L, "Bishkek", 5, "pcs", 100.0, 10.0, 490.0, 3, 0))
        );

        assertThat(invoice.getInvoiceNumber()).startsWith("INV-");
        assertThat(invoice.getUserId()).isEqualTo(USER_ID);
        assertThat(invoice.getOperation()).isEqualTo(InvoiceOperation.SELL);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ACTIVE);
        assertThat(invoice.getTotalAmount()).isEqualTo(490.0);
        assertThat(invoice.getCurrency()).isEqualTo("som");
        assertThat(invoice.getProductSummary()).isEqualTo("Atlas");
        assertThat(invoice.getItemCount()).isEqualTo(1);
        assertThat(invoice.getItems()).hasSize(1);
        assertThat(invoice.getItems().get(0))
                .extracting("productId", "productName", "locationId", "locationName", "quantity",
                        "unitPrice", "discount", "subtotal", "stockBefore", "stockAfter")
                .containsExactly(10L, "Atlas", 20L, "Bishkek", 5, 100.0, 10.0, 490.0, 3, 0);

        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(captor.capture());
        assertThat(captor.getValue().getItems().get(0).getInvoice()).isSameAs(captor.getValue());
    }

    @Test
    void saveAddInvoiceHasNullTotalAndProductSummaryForMultipleItems() {
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Invoice invoice = invoiceService.saveInvoice(
                USER_ID,
                InvoiceOperation.ADD,
                null,
                List.of(
                        line(10L, "Atlas", 20L, "Bishkek", 4, "pcs", 100.0, 0.0, null, 3, 7),
                        line(11L, "Silk", 21L, "Osh", 2, "meters", 50.0, 0.0, null, 1, 3)
                )
        );

        assertThat(invoice.getTotalAmount()).isNull();
        assertThat(invoice.getOperation()).isEqualTo(InvoiceOperation.ADD);
        assertThat(invoice.getProductSummary()).isEqualTo("Atlas +1 more");
        assertThat(invoice.getItemCount()).isEqualTo(2);
        assertThat(invoice.getItems().get(1).getSubtotal()).isNull();
    }

    @Test
    void validateUndoInvoiceRejectsWrongOwnerOrVoidedInvoices() {
        when(invoiceRepository.findOwnedForUpdate(99L, USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> invoiceService.validateUndoInvoice(99L, USER_ID, InvoiceOperation.SELL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invoice not found");

        Invoice invoice = new Invoice();
        invoice.setId(100L);
        invoice.setOperation(InvoiceOperation.SELL);
        invoice.setStatus(InvoiceStatus.VOIDED);
        when(invoiceRepository.findOwnedForUpdate(100L, USER_ID)).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceService.validateUndoInvoice(100L, USER_ID, InvoiceOperation.SELL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invoice is already voided");
    }

    @Test
    void voidInvoiceMarksStatusAndTimestamp() {
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Invoice invoice = new Invoice();
        invoice.setId(55L);
        invoice.setUserId(USER_ID);
        invoice.setStatus(InvoiceStatus.ACTIVE);
        invoice.setCustomer(customer(9L, USER_ID, "Aizada"));
        invoice.setAssignedAt(LocalDateTime.now().minusDays(1));
        when(invoiceRepository.findOwnedForUpdate(55L, USER_ID)).thenReturn(Optional.of(invoice));
        when(customerRepository.findOwnedForUpdate(USER_ID, List.of(9L)))
                .thenReturn(List.of(invoice.getCustomer()));

        invoiceService.voidInvoice(invoice);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VOIDED);
        assertThat(invoice.getVoidedAt()).isNotNull();
        assertThat(invoice.getCustomer()).isNull();
        assertThat(invoice.getAssignedAt()).isNull();
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void assignCustomerSetsRelationshipAndReturnsAdditiveInvoiceFields() {
        Invoice invoice = eligibleSellInvoice(55L, 250.0);
        Customer customer = customer(9L, USER_ID, "Aizada");
        when(invoiceRepository.findOwnedForUpdate(55L, USER_ID)).thenReturn(Optional.of(invoice));
        when(customerRepository.findOwnedForUpdate(USER_ID, List.of(9L)))
                .thenReturn(List.of(customer));
        when(invoiceRepository.save(invoice)).thenReturn(invoice);

        InvoiceDetailResponse response = invoiceService.assignCustomer(55L, 9L, USER_ID);

        assertThat(invoice.getCustomer()).isSameAs(customer);
        assertThat(invoice.getAssignedAt()).isNotNull();
        assertThat(response.getCustomerId()).isEqualTo(9L);
        assertThat(response.getCustomerName()).isEqualTo("Aizada");
        assertThat(response.getAssignedAt()).isEqualTo(invoice.getAssignedAt());
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void reassignCustomerMovesInvoiceAndSameAssignmentIsIdempotent() {
        Invoice invoice = eligibleSellInvoice(55L, 250.0);
        Customer first = customer(8L, USER_ID, "Aizada");
        Customer second = customer(9L, USER_ID, "Bakyt");
        LocalDateTime originalAssignedAt = LocalDateTime.now().minusDays(1);
        invoice.setCustomer(first);
        invoice.setAssignedAt(originalAssignedAt);
        when(invoiceRepository.findOwnedForUpdate(55L, USER_ID)).thenReturn(Optional.of(invoice));
        when(customerRepository.findOwnedForUpdate(USER_ID, List.of(8L, 9L)))
                .thenReturn(List.of(first, second));
        when(customerRepository.findOwnedForUpdate(USER_ID, List.of(9L)))
                .thenReturn(List.of(second));

        invoiceService.assignCustomer(55L, 9L, USER_ID);
        LocalDateTime reassignedAt = invoice.getAssignedAt();
        invoiceService.assignCustomer(55L, 9L, USER_ID);

        assertThat(invoice.getCustomer()).isSameAs(second);
        assertThat(reassignedAt).isAfter(originalAssignedAt);
        assertThat(invoice.getAssignedAt()).isEqualTo(reassignedAt);
        verify(invoiceRepository, times(1)).save(invoice);
    }

    @Test
    void deassignCustomerClearsAssignmentAndRepeatedDeletionIsHarmless() {
        Invoice invoice = eligibleSellInvoice(55L, 250.0);
        invoice.setCustomer(customer(9L, USER_ID, "Aizada"));
        invoice.setAssignedAt(LocalDateTime.now());
        when(invoiceRepository.findOwnedForUpdate(55L, USER_ID)).thenReturn(Optional.of(invoice));
        when(customerRepository.findOwnedForUpdate(USER_ID, List.of(9L)))
                .thenReturn(List.of(invoice.getCustomer()));

        InvoiceDetailResponse first = invoiceService.deassignCustomer(55L, USER_ID);
        InvoiceDetailResponse second = invoiceService.deassignCustomer(55L, USER_ID);

        assertThat(first.getCustomerId()).isNull();
        assertThat(second.getCustomerId()).isNull();
        assertThat(invoice.getCustomer()).isNull();
        assertThat(invoice.getAssignedAt()).isNull();
        verify(invoiceRepository, times(1)).save(invoice);
    }

    @Test
    void assignmentRejectsAddVoidedAndNonPositiveInvoicesBeforeCustomerLookup() {
        Invoice invoice = eligibleSellInvoice(55L, 250.0);
        when(invoiceRepository.findOwnedForUpdate(55L, USER_ID)).thenReturn(Optional.of(invoice));

        invoice.setOperation(InvoiceOperation.ADD);
        assertThatThrownBy(() -> invoiceService.assignCustomer(55L, 9L, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only SELL invoices");

        invoice.setOperation(InvoiceOperation.SELL);
        invoice.setStatus(InvoiceStatus.VOIDED);
        assertThatThrownBy(() -> invoiceService.assignCustomer(55L, 9L, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only active invoices");

        invoice.setStatus(InvoiceStatus.ACTIVE);
        invoice.setTotalAmount(0.0);
        assertThatThrownBy(() -> invoiceService.assignCustomer(55L, 9L, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("positive total");

        verifyNoInteractions(customerRepository);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void assignmentHidesForeignInvoiceAndForeignCustomer() {
        when(invoiceRepository.findOwnedForUpdate(55L, USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> invoiceService.assignCustomer(55L, 9L, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invoice not found");

        Invoice invoice = eligibleSellInvoice(56L, 250.0);
        when(invoiceRepository.findOwnedForUpdate(56L, USER_ID)).thenReturn(Optional.of(invoice));
        when(customerRepository.findOwnedForUpdate(USER_ID, List.of(9L))).thenReturn(List.of());
        assertThatThrownBy(() -> invoiceService.assignCustomer(56L, 9L, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    void customerFilterKeepsInvoicePaginationContract() {
        Invoice invoice = eligibleSellInvoice(55L, 250.0);
        invoice.setCustomer(customer(9L, USER_ID, "Aizada"));
        PageRequest pageable = PageRequest.of(0, 1);
        when(invoiceRepository.findAll(
                org.mockito.ArgumentMatchers.<Specification<Invoice>>any(),
                any(Pageable.class)
        ))
                .thenReturn(new PageImpl<>(List.of(invoice), pageable, 2));
        InvoiceSearchRequest request = new InvoiceSearchRequest(null, null, null, null, null, null, 9L);

        InvoicePageResponse response = invoiceService.search(USER_ID, request, 0, 1, "createdAt,desc");

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getCustomerId()).isEqualTo(9L);
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(1);
        assertThat(response.getTotalItems()).isEqualTo(2);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.isHasNext()).isTrue();
        verify(invoiceRepository).findAll(
                org.mockito.ArgumentMatchers.<Specification<Invoice>>any(),
                any(Pageable.class)
        );
    }

    private InvoiceService.InvoiceLineSnapshot line(
            Long productId,
            String productName,
            Long locationId,
            String locationName,
            int quantity,
            String unit,
            Double unitPrice,
            Double discount,
            Double subtotal,
            int before,
            int after
    ) {
        return new InvoiceService.InvoiceLineSnapshot(
                productId,
                productName,
                locationId,
                locationName,
                quantity,
                unit,
                unitPrice,
                discount,
                subtotal,
                before,
                after
        );
    }

    private Invoice eligibleSellInvoice(Long id, double totalAmount) {
        Invoice invoice = new Invoice();
        invoice.setId(id);
        invoice.setInvoiceNumber("INV-20260716-TEST");
        invoice.setUserId(USER_ID);
        invoice.setOperation(InvoiceOperation.SELL);
        invoice.setStatus(InvoiceStatus.ACTIVE);
        invoice.setCreatedAt(LocalDateTime.now());
        invoice.setTotalAmount(totalAmount);
        invoice.setCurrency("som");
        invoice.setProductSummary("Atlas");
        return invoice;
    }

    private Customer customer(Long id, Long userId, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setUserId(userId);
        customer.setName(name);
        return customer;
    }
}
