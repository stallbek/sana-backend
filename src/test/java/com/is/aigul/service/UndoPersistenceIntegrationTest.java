package com.is.aigul.service;

import com.is.aigul.dto.CommandDTO;
import com.is.aigul.model.*;
import com.is.aigul.repository.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Import({ActionService.class, InvoiceService.class, LogbookService.class})
class UndoPersistenceIntegrationTest {
    @Autowired ActionService actions;
    @Autowired InvoiceService invoices;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired ProductRepository products;
    @Autowired ProductLocationRepository locations;
    @Autowired PlatformTransactionManager transactions;

    @Test void foreignUserCannotReadOrReverseOwnedInvoice() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> actions.undo(command(fixture.invoiceId()), 8L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(locations.findOwnedForUpdate(fixture.locationId(), 8L)).isEmpty();
        assertThat(locations.findById(fixture.locationId()).orElseThrow().getQuantity()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAndRepeatedUndoRestoresStockExactlyOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Fixture fixture = tx.execute(status -> fixture());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> undo = () -> {
                ready.countDown(); start.await(5, TimeUnit.SECONDS);
                try { actions.undo(command(fixture.invoiceId()), 7L); return true; }
                catch (IllegalArgumentException e) { return false; }
            };
            Future<Boolean> first = pool.submit(undo); Future<Boolean> second = pool.submit(undo);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            Integer stock = tx.execute(status -> locations.findById(fixture.locationId()).orElseThrow().getQuantity());
            InvoiceStatus invoiceStatus = tx.execute(status -> invoiceRepository.findById(fixture.invoiceId()).orElseThrow().getStatus());
            assertThat(stock).isEqualTo(3);
            assertThat(invoiceStatus).isEqualTo(InvoiceStatus.VOIDED);
            assertThatThrownBy(() -> actions.undo(command(fixture.invoiceId()), 7L)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            start.countDown(); pool.shutdownNow();
        }
    }

    private Fixture fixture() {
        Product product = new Product(); product.setName("Undo-" + java.util.UUID.randomUUID());
        product.setUserId(7L); product.setPrice(10d); products.saveAndFlush(product);
        ProductLocation location = new ProductLocation(); location.setUserId(7L); location.setProduct(product);
        location.setLocationName("Shop"); location.setQuantity(0); locations.saveAndFlush(location);
        Invoice invoice = invoices.saveInvoice(7L, InvoiceOperation.SELL, 50d,
                List.of(new InvoiceService.InvoiceLineSnapshot(product.getId(), product.getName(), location.getId(),
                        "Shop", 5, "units", 10d, 0d, 50d, 3, 0)));
        invoiceRepository.flush();
        return new Fixture(invoice.getId(), location.getId());
    }
    private CommandDTO command(Long id) {
        CommandDTO cmd = new CommandDTO(); cmd.setOperation("SELL"); cmd.setInvoiceId(id); return cmd;
    }
    private record Fixture(Long invoiceId, Long locationId) { }
}
