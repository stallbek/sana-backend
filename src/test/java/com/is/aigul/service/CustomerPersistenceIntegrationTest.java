package com.is.aigul.service;

import com.is.aigul.dto.CustomerPageResponse;
import com.is.aigul.dto.CustomerRequest;
import com.is.aigul.dto.CustomerSummaryResponse;
import com.is.aigul.dto.InvoicePageResponse;
import com.is.aigul.dto.InvoiceSearchRequest;
import com.is.aigul.model.Customer;
import com.is.aigul.model.Invoice;
import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import com.is.aigul.repository.CustomerRepository;
import com.is.aigul.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Import({CustomerService.class, InvoiceService.class})
class CustomerPersistenceIntegrationTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    @Autowired
    private CustomerService customerService;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;

    @Test
    void totalsAndSearchAreCalculatedFromOwnedActiveSellAssignments() {
        Customer aizada = createCustomer(USER_ID, "Aizada", "+996 (555) 12-34-56");
        createCustomer(USER_ID, "Aizada", null);
        Customer foreignCustomer = createCustomer(OTHER_USER_ID, "Aizada Foreign", "+996555123456");

        invoiceRepository.save(invoice(USER_ID, "ONE", InvoiceOperation.SELL, InvoiceStatus.ACTIVE, 100.0, aizada));
        invoiceRepository.save(invoice(USER_ID, "TWO", InvoiceOperation.SELL, InvoiceStatus.ACTIVE, 200.0, aizada));
        invoiceRepository.save(invoice(USER_ID, "VOID", InvoiceOperation.SELL, InvoiceStatus.VOIDED, 50.0, aizada));
        invoiceRepository.save(invoice(USER_ID, "ADD", InvoiceOperation.ADD, InvoiceStatus.ACTIVE, null, aizada));
        invoiceRepository.save(invoice(OTHER_USER_ID, "FOREIGN", InvoiceOperation.SELL, InvoiceStatus.ACTIVE, 900.0, foreignCustomer));

        CustomerSummaryResponse detail = customerService.get(aizada.getId(), USER_ID);
        CustomerPageResponse nameResults = customerService.search(USER_ID, 0, 20, "aiz");
        CustomerPageResponse phoneResults = customerService.search(USER_ID, 0, 20, "555 12");

        assertThat(detail.getTotalOwed()).isEqualTo(300.0);
        assertThat(detail.getAssignedInvoiceCount()).isEqualTo(2);
        assertThat(nameResults.getItems()).hasSize(2);
        assertThat(phoneResults.getItems())
                .extracting("id")
                .containsExactly(aizada.getId());
        assertThat(phoneResults.getItems())
                .extracting("totalOwed", "assignedInvoiceCount")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(300.0, 2L));
    }

    @Test
    void reassignmentAndDeassignmentTransferCalculatedDebt() {
        Customer first = createCustomer(USER_ID, "Aizada", null);
        Customer second = createCustomer(USER_ID, "Bakyt", null);
        Invoice invoice = invoiceRepository.save(
                invoice(USER_ID, "TRANSFER", InvoiceOperation.SELL, InvoiceStatus.ACTIVE, 125.0, null)
        );

        invoiceService.assignCustomer(invoice.getId(), first.getId(), USER_ID);
        assertThat(customerService.get(first.getId(), USER_ID).getTotalOwed()).isEqualTo(125.0);
        assertThat(customerService.get(second.getId(), USER_ID).getTotalOwed()).isZero();

        invoiceService.assignCustomer(invoice.getId(), second.getId(), USER_ID);
        assertThat(customerService.get(first.getId(), USER_ID).getTotalOwed()).isZero();
        assertThat(customerService.get(second.getId(), USER_ID).getTotalOwed()).isEqualTo(125.0);

        invoiceService.deassignCustomer(invoice.getId(), USER_ID);
        assertThat(customerService.get(second.getId(), USER_ID).getTotalOwed()).isZero();
    }

    @Test
    void voidClearsAssignmentAndCustomerFilterRemainsPaginated() {
        Customer customer = createCustomer(USER_ID, "Aizada", null);
        Invoice first = invoiceRepository.save(
                invoice(USER_ID, "PAGE1", InvoiceOperation.SELL, InvoiceStatus.ACTIVE, 100.0, customer)
        );
        invoiceRepository.save(
                invoice(USER_ID, "PAGE2", InvoiceOperation.SELL, InvoiceStatus.ACTIVE, 200.0, customer)
        );
        invoiceRepository.save(
                invoice(USER_ID, "OTHER", InvoiceOperation.SELL, InvoiceStatus.ACTIVE, 300.0, null)
        );

        InvoicePageResponse page = invoiceService.search(
                USER_ID,
                new InvoiceSearchRequest(null, null, null, null, null, null, customer.getId()),
                0,
                1,
                "createdAt,asc"
        );

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getTotalItems()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.isHasNext()).isTrue();

        invoiceService.voidInvoice(first);
        Invoice reloaded = invoiceRepository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.VOIDED);
        assertThat(reloaded.getCustomer()).isNull();
        assertThat(reloaded.getAssignedAt()).isNull();
        assertThat(customerService.get(customer.getId(), USER_ID).getTotalOwed()).isEqualTo(200.0);
        assertThat(customerService.get(customer.getId(), USER_ID).getAssignedInvoiceCount()).isEqualTo(1);
    }

    private Customer createCustomer(Long userId, String name, String phone) {
        CustomerSummaryResponse response = customerService.create(userId, new CustomerRequest(name, phone));
        return customerRepository.findById(response.getId()).orElseThrow();
    }

    private Invoice invoice(
            Long userId,
            String suffix,
            InvoiceOperation operation,
            InvoiceStatus status,
            Double total,
            Customer customer
    ) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-20260716-" + suffix);
        invoice.setUserId(userId);
        invoice.setOperation(operation);
        invoice.setStatus(status);
        invoice.setCreatedAt(LocalDateTime.now());
        invoice.setVoidedAt(status == InvoiceStatus.VOIDED ? LocalDateTime.now() : null);
        invoice.setTotalAmount(total);
        invoice.setCurrency("som");
        invoice.setProductSummary("Atlas");
        invoice.setCustomer(customer);
        invoice.setAssignedAt(customer == null ? null : LocalDateTime.now());
        return invoice;
    }
}
