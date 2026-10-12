package com.is.aigul.service;

import com.is.aigul.dto.CustomerPaymentRequest;
import com.is.aigul.dto.CustomerPaymentReversalRequest;
import com.is.aigul.dto.CustomerSummaryResponse;
import com.is.aigul.model.Customer;
import com.is.aigul.model.CustomerPaymentEntry;
import com.is.aigul.model.CustomerPaymentEntryType;
import com.is.aigul.model.CustomerPaymentMethod;
import com.is.aigul.model.Logbook;
import com.is.aigul.repository.CustomerPaymentEntryRepository;
import com.is.aigul.repository.CustomerRepository;
import com.is.aigul.repository.LogbookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerPaymentServiceTest {
    private static final Long USER_ID = 7L;
    private static final Long CUSTOMER_ID = 9L;

    @Mock CustomerPaymentEntryRepository paymentRepository;
    @Mock CustomerRepository customerRepository;
    @Mock CustomerService customerService;
    @Mock LogbookRepository logbookRepository;

    private CustomerPaymentService service;
    private Customer customer;

    @BeforeEach
    void setUp() {
        service = new CustomerPaymentService(
                paymentRepository, customerRepository, customerService, logbookRepository);
        customer = new Customer();
        customer.setId(CUSTOMER_ID);
        customer.setUserId(USER_ID);
        customer.setName("Aizada");
    }

    @Test
    void recordsPaymentUpdatesBalanceAndWritesStructuredLog() {
        when(customerRepository.findOwnedForUpdate(CUSTOMER_ID, USER_ID))
                .thenReturn(Optional.of(customer));
        when(customerService.get(CUSTOMER_ID, USER_ID))
                .thenReturn(summary(10000.0, 0.0), summary(5000.0, 5000.0));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CustomerPaymentEntry entry = invocation.getArgument(0);
            entry.setId(41L);
            return entry;
        });

        var response = service.recordPayment(
                USER_ID,
                CUSTOMER_ID,
                " payment-1 ",
                new CustomerPaymentRequest(
                        new BigDecimal("5000.00"),
                        CustomerPaymentMethod.CASH,
                        LocalDateTime.now().minusMinutes(1),
                        "  counter  "));

        assertThat(response.payment().getAmount()).isEqualByComparingTo("5000.00");
        assertThat(response.payment().getNote()).isEqualTo("counter");
        assertThat(response.customer().getTotalOwed()).isEqualTo(5000.0);
        ArgumentCaptor<Logbook> log = ArgumentCaptor.forClass(Logbook.class);
        verify(logbookRepository).save(log.capture());
        assertThat(log.getValue().getActionType()).isEqualTo("PAYMENT");
        assertThat(log.getValue().getAmount()).isEqualTo(5000.0);
        assertThat(log.getValue().getPaymentEntryId()).isEqualTo(41L);
        assertThat(log.getValue().getCustomerId()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void rejectsInvalidAmountsFutureDatesAndOverpayment() {
        assertThatThrownBy(() -> service.recordPayment(
                USER_ID, CUSTOMER_ID, "key",
                new CustomerPaymentRequest(
                        new BigDecimal("1.001"), CustomerPaymentMethod.CARD, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("two decimal");

        assertThatThrownBy(() -> service.recordPayment(
                USER_ID, CUSTOMER_ID, "key",
                new CustomerPaymentRequest(
                        new BigDecimal("100000000000000000.00"),
                        CustomerPaymentMethod.CARD, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("too large");

        assertThatThrownBy(() -> service.recordPayment(
                USER_ID, CUSTOMER_ID, "key",
                new CustomerPaymentRequest(
                        BigDecimal.ONE, CustomerPaymentMethod.CARD,
                        LocalDateTime.now().plusDays(1), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("future");

        when(customerRepository.findOwnedForUpdate(CUSTOMER_ID, USER_ID))
                .thenReturn(Optional.of(customer));
        when(customerService.get(CUSTOMER_ID, USER_ID)).thenReturn(summary(10.0, 0.0));
        assertThatThrownBy(() -> service.recordPayment(
                USER_ID, CUSTOMER_ID, "over",
                new CustomerPaymentRequest(
                        new BigDecimal("10.01"), CustomerPaymentMethod.CARD, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot exceed");
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void identicalIdempotentReplayReturnsExistingAndDifferentReplayConflicts() {
        CustomerPaymentEntry existing = payment(
                41L, CustomerPaymentEntryType.PAYMENT, new BigDecimal("10.00"));
        existing.setMethod(CustomerPaymentMethod.CASH);
        existing.setNote("note");
        var request = new CustomerPaymentRequest(
                new BigDecimal("10.00"), CustomerPaymentMethod.CASH, null, " note ");

        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, "same"))
                .thenReturn(Optional.empty());
        when(customerRepository.findOwnedForUpdate(CUSTOMER_ID, USER_ID))
                .thenReturn(Optional.of(customer));
        when(customerService.get(CUSTOMER_ID, USER_ID))
                .thenReturn(summary(100.0, 0.0), summary(90.0, 10.0));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CustomerPaymentEntry entry = invocation.getArgument(0);
            entry.setId(41L);
            existing.setRequestFingerprint(entry.getRequestFingerprint());
            return entry;
        });
        service.recordPayment(USER_ID, CUSTOMER_ID, "same", request);

        when(paymentRepository.findByUserIdAndIdempotencyKey(USER_ID, "same"))
                .thenReturn(Optional.of(existing));
        var replay = service.recordPayment(USER_ID, CUSTOMER_ID, "same", request);
        assertThat(replay.payment().getId()).isEqualTo(41L);

        assertThatThrownBy(() -> service.recordPayment(
                USER_ID, CUSTOMER_ID, "same",
                new CustomerPaymentRequest(
                        new BigDecimal("9.00"), CustomerPaymentMethod.CASH, null, "note")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different request");
    }

    @Test
    void fullReversalIsImmutableAndDuplicateIsRejected() {
        CustomerPaymentEntry original = payment(
                41L, CustomerPaymentEntryType.PAYMENT, new BigDecimal("25.00"));
        when(customerRepository.findOwnedForUpdate(CUSTOMER_ID, USER_ID))
                .thenReturn(Optional.of(customer));
        when(paymentRepository.findByIdAndUserIdAndCustomer_Id(41L, USER_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(original));
        when(customerService.get(CUSTOMER_ID, USER_ID)).thenReturn(summary(100.0, 0.0));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CustomerPaymentEntry entry = invocation.getArgument(0);
            entry.setId(42L);
            return entry;
        });

        var response = service.reversePayment(
                USER_ID, CUSTOMER_ID, 41L, "reverse-1",
                new CustomerPaymentReversalRequest(" wrong payment "));

        assertThat(response.payment().getType()).isEqualTo(CustomerPaymentEntryType.REVERSAL);
        assertThat(response.payment().getAmount()).isEqualByComparingTo("25.00");
        assertThat(response.payment().getReversalOfPaymentId()).isEqualTo(41L);
        ArgumentCaptor<Logbook> log = ArgumentCaptor.forClass(Logbook.class);
        verify(logbookRepository).save(log.capture());
        assertThat(log.getValue().getAmount()).isEqualTo(-25.0);

        when(paymentRepository.existsByReversalOfPayment_Id(41L)).thenReturn(true);
        assertThatThrownBy(() -> service.reversePayment(
                USER_ID, CUSTOMER_ID, 41L, "reverse-2",
                new CustomerPaymentReversalRequest("again")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already reversed");
    }

    private CustomerPaymentEntry payment(
            Long id, CustomerPaymentEntryType type, BigDecimal amount) {
        CustomerPaymentEntry entry = new CustomerPaymentEntry();
        entry.setId(id);
        entry.setUserId(USER_ID);
        entry.setCustomer(customer);
        entry.setType(type);
        entry.setAmount(amount);
        entry.setCurrency("som");
        entry.setOccurredAt(LocalDateTime.now().minusMinutes(1));
        entry.setCreatedAt(LocalDateTime.now());
        return entry;
    }

    private CustomerSummaryResponse summary(double owed, double paid) {
        return CustomerSummaryResponse.from(customer, owed + paid, paid, 1);
    }
}
