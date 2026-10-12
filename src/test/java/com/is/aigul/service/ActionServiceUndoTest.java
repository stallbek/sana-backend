package com.is.aigul.service;

import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.exception.UnsupportedCommandException;
import com.is.aigul.model.*;
import com.is.aigul.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActionServiceUndoTest {
    private static final Long USER = 7L;
    @Mock ProductRepository products;
    @Mock ProductLocationRepository locations;
    @Mock LogbookService logs;
    @Mock InvoiceService invoices;
    private ActionService service;

    @BeforeEach void setup() { service = new ActionService(products, locations, logs, invoices); }

    @Test void rejectsMissingInvoiceWithoutMutation() {
        CommandDTO cmd = command("SELL"); cmd.setInvoiceId(null);
        assertThatThrownBy(() -> service.undo(cmd, USER)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(products, locations, logs, invoices);
    }

    @Test void rejectsMoveUndoWithoutMutation() {
        assertThatThrownBy(() -> service.undo(command("MOVE"), USER)).isInstanceOf(UnsupportedCommandException.class);
        verifyNoInteractions(products, locations, logs, invoices);
    }

    @Test void ignoresForgedClientItemsAndRestoresOnlyActualStockDeducted() {
        Invoice invoice = invoice(InvoiceOperation.SELL, line(30L, 3, 0));
        ProductLocation location = location(30L, 0);
        when(invoices.validateUndoInvoice(44L, USER, InvoiceOperation.SELL)).thenReturn(invoice);
        when(locations.findOwnedForUpdate(30L, USER)).thenReturn(Optional.of(location));
        CommandDTO cmd = command("SELL");
        SellItemDTO forged = new SellItemDTO(); forged.setProductName("Other"); forged.setQuantity(999999);
        cmd.setItems(List.of(forged));
        assertThat(service.undo(cmd, USER).getType()).isEqualTo("ACTION_CANCELLED");
        assertThat(location.getQuantity()).isEqualTo(3);
        verify(invoices).voidInvoice(invoice);
        verifyNoInteractions(products);
    }

    @Test void combinesDuplicateSnapshotLocationsBeforeReversal() {
        Invoice invoice = invoice(InvoiceOperation.SELL, line(30L, 7, 4), line(30L, 4, 2));
        ProductLocation location = location(30L, 2);
        when(invoices.validateUndoInvoice(44L, USER, InvoiceOperation.SELL)).thenReturn(invoice);
        when(locations.findOwnedForUpdate(30L, USER)).thenReturn(Optional.of(location));
        service.undo(command("SELL"), USER);
        assertThat(location.getQuantity()).isEqualTo(7);
        verify(locations).save(location);
    }

    @Test void insufficientAddStockDoesNotPartiallyMutateOrVoid() {
        Invoice invoice = invoice(InvoiceOperation.ADD, line(30L, 0, 4), line(31L, 0, 5));
        ProductLocation first = location(30L, 10); ProductLocation second = location(31L, 2);
        when(invoices.validateUndoInvoice(44L, USER, InvoiceOperation.ADD)).thenReturn(invoice);
        when(locations.findOwnedForUpdate(30L, USER)).thenReturn(Optional.of(first));
        when(locations.findOwnedForUpdate(31L, USER)).thenReturn(Optional.of(second));
        assertThatThrownBy(() -> service.undo(command("ADD"), USER)).isInstanceOf(ResponseStatusException.class);
        assertThat(first.getQuantity()).isEqualTo(10); assertThat(second.getQuantity()).isEqualTo(2);
        verify(locations, never()).save(any()); verify(invoices, never()).voidInvoice(any()); verifyNoInteractions(logs);
    }

    @Test void rejectsStockOverflowBeforeMutation() {
        Invoice invoice = invoice(InvoiceOperation.SELL, line(30L, 3, 0));
        ProductLocation location = location(30L, Integer.MAX_VALUE - 1);
        when(invoices.validateUndoInvoice(44L, USER, InvoiceOperation.SELL)).thenReturn(invoice);
        when(locations.findOwnedForUpdate(30L, USER)).thenReturn(Optional.of(location));
        assertThatThrownBy(() -> service.undo(command("SELL"), USER)).isInstanceOf(ArithmeticException.class);
        assertThat(location.getQuantity()).isEqualTo(Integer.MAX_VALUE - 1);
        verify(locations, never()).save(any()); verifyNoInteractions(logs);
    }

    @Test void foreignOrVoidedInvoiceCannotChangeStock() {
        when(invoices.validateUndoInvoice(44L, USER, InvoiceOperation.SELL))
                .thenThrow(new IllegalArgumentException("Invoice not found"));
        assertThatThrownBy(() -> service.undo(command("SELL"), USER)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(locations, products, logs);
    }

    private CommandDTO command(String op) {
        CommandDTO cmd = new CommandDTO(); cmd.setOperation(op); cmd.setInvoiceId(44L); return cmd;
    }
    private Invoice invoice(InvoiceOperation op, InvoiceItem... items) {
        Invoice invoice = new Invoice(); invoice.setId(44L); invoice.setUserId(USER);
        invoice.setOperation(op); invoice.setStatus(InvoiceStatus.ACTIVE);
        for (InvoiceItem item : items) invoice.addItem(item);
        return invoice;
    }
    private InvoiceItem line(Long location, int before, int after) {
        InvoiceItem item = new InvoiceItem(); item.setLocationId(location); item.setStockBefore(before); item.setStockAfter(after);
        return item;
    }
    private ProductLocation location(Long id, int stock) {
        Product product = new Product(); product.setId(10L); product.setName("Atlas"); product.setUserId(USER);
        ProductLocation location = new ProductLocation(); location.setId(id); location.setProduct(product);
        location.setUserId(USER); location.setLocationName("Shop"); location.setQuantity(stock); return location;
    }
}
