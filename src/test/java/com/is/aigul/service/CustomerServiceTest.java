package com.is.aigul.service;

import com.is.aigul.dto.CustomerPageResponse;
import com.is.aigul.dto.CustomerRequest;
import com.is.aigul.dto.CustomerSummaryResponse;
import com.is.aigul.model.Customer;
import com.is.aigul.repository.CustomerDebtSummary;
import com.is.aigul.repository.CustomerRepository;
import com.is.aigul.repository.InvoiceRepository;
import com.is.aigul.repository.CustomerPaymentEntryRepository;
import com.is.aigul.repository.CustomerPaymentBalanceSummary;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private CustomerPaymentEntryRepository paymentEntryRepository;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(
                customerRepository, invoiceRepository, paymentEntryRepository);
    }

    @Test
    void createTrimsNameNormalizesPhoneAndSetsOwnerAndTimestamps() {
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            customer.setId(9L);
            return customer;
        });

        CustomerSummaryResponse response = customerService.create(
                USER_ID,
                new CustomerRequest("  Aizada  ", "+996 (555) 12-34-56")
        );

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(captor.capture());
        Customer saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getName()).isEqualTo("Aizada");
        assertThat(saved.getPhone()).isEqualTo("+996555123456");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(response.getTotalOwed()).isZero();
        assertThat(response.getAssignedInvoiceCount()).isZero();
    }

    @Test
    void createRejectsBlankNameAndInvalidPhone() {
        assertThatThrownBy(() -> customerService.create(USER_ID, new CustomerRequest("  ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Customer name is required");
        assertThatThrownBy(() -> customerService.create(USER_ID, new CustomerRequest("Aizada", "phone")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("must contain at least one digit");
        verify(customerRepository, never()).save(any());
    }

    @Test
    void searchStaysPaginatedAndAddsCalculatedTotalsAndCounts() {
        Customer aizada = customer(9L, "Aizada", "+996555123456");
        Customer bakyt = customer(10L, "Bakyt", null);
        PageRequest pageRequest = PageRequest.of(0, 2);
        when(customerRepository.findAll(
                org.mockito.ArgumentMatchers.<Specification<Customer>>any(),
                any(Pageable.class)
        ))
                .thenReturn(new PageImpl<>(List.of(aizada, bakyt), pageRequest, 3));
        CustomerDebtSummary summary = mock(CustomerDebtSummary.class);
        when(summary.getCustomerId()).thenReturn(9L);
        when(summary.getTotalOwed()).thenReturn(750.0);
        when(summary.getAssignedInvoiceCount()).thenReturn(2L);
        when(invoiceRepository.summarizeActiveSellAssignments(USER_ID, List.of(9L, 10L)))
                .thenReturn(List.of(summary));
        CustomerPaymentBalanceSummary paymentSummary = mock(CustomerPaymentBalanceSummary.class);
        when(paymentSummary.getCustomerId()).thenReturn(9L);
        when(paymentSummary.getNetPaidAmount()).thenReturn(new BigDecimal("250.00"));
        when(paymentEntryRepository.summarizeNetPayments(USER_ID, List.of(9L, 10L)))
                .thenReturn(List.of(paymentSummary));

        CustomerPageResponse response = customerService.search(USER_ID, 0, 2, "555 12");

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getGrossAssignedAmount()).isEqualTo(750.0);
        assertThat(response.getItems().get(0).getNetPaidAmount()).isEqualTo(250.0);
        assertThat(response.getItems().get(0).getTotalOwed()).isEqualTo(500.0);
        assertThat(response.getItems().get(0).getCreditBalance()).isZero();
        assertThat(response.getItems().get(0).getCurrency()).isEqualTo("som");
        assertThat(response.getItems().get(0).getAssignedInvoiceCount()).isEqualTo(2);
        assertThat(response.getItems().get(1).getTotalOwed()).isZero();
        assertThat(response.getItems().get(1).getAssignedInvoiceCount()).isZero();
        assertThat(response.getTotalItems()).isEqualTo(3);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.isHasNext()).isTrue();
    }

    @Test
    void editAllowsDuplicateNamesAndPreservesCalculatedDebt() {
        Customer customer = customer(9L, "Aizada", null);
        LocalDateTime createdAt = customer.getCreatedAt();
        when(customerRepository.findByIdAndUserId(9L, USER_ID)).thenReturn(Optional.of(customer));
        when(customerRepository.save(customer)).thenReturn(customer);
        CustomerDebtSummary summary = mock(CustomerDebtSummary.class);
        when(summary.getCustomerId()).thenReturn(9L);
        when(summary.getTotalOwed()).thenReturn(250.0);
        when(summary.getAssignedInvoiceCount()).thenReturn(1L);
        when(invoiceRepository.summarizeActiveSellAssignments(USER_ID, List.of(9L))).thenReturn(List.of(summary));

        CustomerSummaryResponse response = customerService.update(
                9L,
                USER_ID,
                new CustomerRequest("Bakyt", "0555-123-456")
        );

        assertThat(customer.getName()).isEqualTo("Bakyt");
        assertThat(customer.getPhone()).isEqualTo("0555123456");
        assertThat(customer.getCreatedAt()).isEqualTo(createdAt);
        assertThat(customer.getUpdatedAt()).isAfterOrEqualTo(createdAt);
        assertThat(response.getTotalOwed()).isEqualTo(250.0);
        assertThat(response.getAssignedInvoiceCount()).isEqualTo(1);
    }

    @Test
    void getUsesOwnerScopedLookupAndCalculatedTotals() {
        Customer customer = customer(9L, "Aizada", null);
        when(customerRepository.findByIdAndUserId(9L, USER_ID)).thenReturn(Optional.of(customer));
        when(invoiceRepository.summarizeActiveSellAssignments(USER_ID, List.of(9L))).thenReturn(List.of());

        CustomerSummaryResponse response = customerService.get(9L, USER_ID);

        assertThat(response.getName()).isEqualTo("Aizada");
        assertThat(response.getTotalOwed()).isZero();
    }

    @Test
    void crossUserCustomerAccessReturnsNotFound() {
        when(customerRepository.findByIdAndUserId(9L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.get(9L, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Customer not found");
        assertThatThrownBy(() -> customerService.update(9L, USER_ID, new CustomerRequest("Aizada", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Customer not found");
    }

    private Customer customer(Long id, String name, String phone) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setUserId(USER_ID);
        customer.setName(name);
        customer.setPhone(phone);
        customer.setCreatedAt(LocalDateTime.now().minusDays(1));
        customer.setUpdatedAt(customer.getCreatedAt());
        return customer;
    }
}
