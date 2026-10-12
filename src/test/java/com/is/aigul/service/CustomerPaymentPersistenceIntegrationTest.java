package com.is.aigul.service;

import com.is.aigul.dto.CustomerPaymentRequest;
import com.is.aigul.dto.CustomerPaymentReversalRequest;
import com.is.aigul.dto.CustomerRequest;
import com.is.aigul.model.CustomerPaymentMethod;
import com.is.aigul.model.Invoice;
import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import com.is.aigul.repository.CustomerPaymentEntryRepository;
import com.is.aigul.repository.InvoiceRepository;
import com.is.aigul.repository.LogbookRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Import({CustomerService.class, CustomerPaymentService.class, InvoiceService.class})
class CustomerPaymentPersistenceIntegrationTest {
    private static final Long USER_ID = 7L;

    @Autowired CustomerService customerService;
    @Autowired CustomerPaymentService paymentService;
    @Autowired InvoiceService invoiceService;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired CustomerPaymentEntryRepository paymentRepository;
    @Autowired LogbookRepository logbookRepository;

    @Test
    void paymentReversalHistoryAndCreditFollowAssignments() {
        Long customerId = customerService.create(
                USER_ID, new CustomerRequest("Aizada", null)).getId();
        Invoice first = invoiceRepository.save(invoice("PAY-1", 100.0));
        invoiceService.assignCustomer(first.getId(), customerId, USER_ID);

        var payment = paymentService.recordPayment(
                USER_ID,
                customerId,
                "payment-1",
                new CustomerPaymentRequest(
                        new BigDecimal("80.00"),
                        CustomerPaymentMethod.BANK_TRANSFER,
                        LocalDateTime.now().minusDays(1),
                        "deposit"));
        assertThat(payment.customer().getGrossAssignedAmount()).isEqualTo(100.0);
        assertThat(payment.customer().getNetPaidAmount()).isEqualTo(80.0);
        assertThat(payment.customer().getTotalOwed()).isEqualTo(20.0);

        invoiceService.deassignCustomer(first.getId(), USER_ID);
        assertThat(customerService.get(customerId, USER_ID).getCreditBalance()).isEqualTo(80.0);

        Invoice second = invoiceRepository.save(invoice("PAY-2", 120.0));
        invoiceService.assignCustomer(second.getId(), customerId, USER_ID);
        assertThat(customerService.get(customerId, USER_ID).getTotalOwed()).isEqualTo(40.0);

        var reversal = paymentService.reversePayment(
                USER_ID,
                customerId,
                payment.payment().getId(),
                "reversal-1",
                new CustomerPaymentReversalRequest("bank transfer returned"));
        assertThat(reversal.customer().getNetPaidAmount()).isZero();
        assertThat(reversal.customer().getTotalOwed()).isEqualTo(120.0);

        var page = paymentService.history(USER_ID, customerId, 0, 1);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getTotalItems()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.isHasNext()).isTrue();
        assertThat(page.getItems().get(0).getId()).isEqualTo(reversal.payment().getId());
        assertThat(paymentRepository.count()).isEqualTo(2);
        assertThat(logbookRepository.findByUserIdOrderByTimestampDescIdDesc(USER_ID))
                .extracting("actionType")
                .containsExactly("PAYMENT_REVERSED", "PAYMENT");
    }

    @Test
    void userIsolationHidesForeignCustomerAndPayment() {
        Long foreignCustomerId = customerService.create(
                8L, new CustomerRequest("Foreign", null)).getId();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                paymentService.history(USER_ID, foreignCustomerId, 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPaymentsCannotJointlyExceedDebt() throws Exception {
        Long customerId = customerService.create(
                USER_ID, new CustomerRequest("Concurrent", null)).getId();
        Invoice invoice = invoiceRepository.save(invoice("PAY-CONCURRENT", 100.0));
        invoiceService.assignCustomer(invoice.getId(), customerId, USER_ID);

        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() ->
                    attemptPayment(start, customerId, "concurrent-1"));
            Future<Boolean> second = executor.submit(() ->
                    attemptPayment(start, customerId, "concurrent-2"));
            start.countDown();

            assertThat(first.get() ^ second.get()).isTrue();
            assertThat(customerService.get(customerId, USER_ID).getNetPaidAmount())
                    .isEqualTo(60.0);
            assertThat(customerService.get(customerId, USER_ID).getTotalOwed())
                    .isEqualTo(40.0);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean attemptPayment(
            CountDownLatch start, Long customerId, String idempotencyKey) throws Exception {
        start.await();
        try {
            paymentService.recordPayment(
                    USER_ID,
                    customerId,
                    idempotencyKey,
                    new CustomerPaymentRequest(
                            new BigDecimal("60.00"),
                            CustomerPaymentMethod.CASH,
                            null,
                            null));
            return true;
        } catch (ResponseStatusException exception) {
            assertThat(exception.getStatusCode().value()).isEqualTo(409);
            return false;
        }
    }

    private Invoice invoice(String suffix, double total) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-20260728-" + suffix);
        invoice.setUserId(USER_ID);
        invoice.setOperation(InvoiceOperation.SELL);
        invoice.setStatus(InvoiceStatus.ACTIVE);
        invoice.setCreatedAt(LocalDateTime.now());
        invoice.setTotalAmount(total);
        invoice.setCurrency("som");
        invoice.setProductSummary("Atlas");
        return invoice;
    }
}
