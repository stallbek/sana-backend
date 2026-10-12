package com.is.aigul.service;

import com.is.aigul.dto.ActionResponse;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.model.Invoice;
import com.is.aigul.model.InvoiceOperation;
import com.is.aigul.model.InvoiceStatus;
import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import com.is.aigul.repository.ProductLocationRepository;
import com.is.aigul.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionServiceSellTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ProductRepository productRepo;
    @Mock
    private ProductLocationRepository productLocationRepo;
    @Mock
    private LogbookService logbookService;
    @Mock
    private InvoiceService invoiceService;

    private ActionService actionService;

    @BeforeEach
    void setUp() {
        actionService = new ActionService(
                productRepo,
                productLocationRepo,
                logbookService,
                invoiceService
        );
    }

    @Test
    void sellUsesRequestedQuantityForBillAndLogbookWhenStockIsInsufficient() {
        Product product = product("Atlas", 100.0);
        ProductLocation location = location(product, "Bishkek", 3);
        when(productRepo.findByNameIgnoreCaseAndUserId("Atlas", USER_ID)).thenReturn(List.of(product));
        when(invoiceService.saveInvoice(eq(USER_ID), eq(InvoiceOperation.SELL), eq(490.0), anyList()))
                .thenReturn(invoice(11L, "INV-20260709-ABCDEFGH"));

        ActionResponse response = actionService.sell(command(item("Atlas", "Bishkek", 5, 100.0, 10.0)), USER_ID);

        assertThat(response.getType()).isEqualTo("PRODUCTS_SOLD");
        assertThat(location.getQuantity()).isZero();
        assertThat(response.getInsights()).contains(
                "STOCK_SHORTAGE|product=Atlas|location=Bishkek|requested=5|available=3|remainingStock=0");

        Map<String, Object> data = responseData(response);
        assertThat(data.get("total")).isEqualTo(490.0);
        assertThat(data.get("invoiceId")).isEqualTo(11L);
        assertThat(data.get("invoiceNumber")).isEqualTo("INV-20260709-ABCDEFGH");
        assertThat(data.get("invoiceStatus")).isEqualTo("ACTIVE");
        List<Map<String, Object>> items = responseItems(data);
        assertThat(items.get(0))
                .containsEntry("sold", 5)
                .containsEntry("before", 3)
                .containsEntry("after", 0)
                .containsEntry("subtotal", 490.0);

        verify(productLocationRepo).save(location);
        verify(invoiceService).saveInvoice(eq(USER_ID), eq(InvoiceOperation.SELL), eq(490.0), anyList());
        verify(logbookService).logAction(
                eq("SELL"),
                eq("System"),
                eq(location),
                eq(5),
                eq(490.0),
                argThat(note -> note.contains("5 pcs")
                        && note.contains("Stock overflow")
                        && note.contains("Available before sale: 3")
                        && note.contains("recorded sale: 5")
                        && note.contains("Check or add stock later")
                ),
                eq(product),
                eq(USER_ID)
        );
    }

    private Product product(String name, double price) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        product.setUserId(USER_ID);
        product.setLocations(new ArrayList<>());
        return product;
    }

    private ProductLocation location(Product product, String name, int quantity) {
        ProductLocation location = new ProductLocation();
        location.setProduct(product);
        location.setLocationName(name);
        location.setQuantity(quantity);
        location.setUserId(USER_ID);
        product.getLocations().add(location);
        return location;
    }

    private CommandDTO command(SellItemDTO... items) {
        CommandDTO command = new CommandDTO();
        command.setOperation("SELL");
        command.setItems(List.of(items));
        return command;
    }

    private SellItemDTO item(String productName, String location, int quantity, double price, double discount) {
        SellItemDTO item = new SellItemDTO();
        item.setProductName(productName);
        item.setLocation(location);
        item.setQuantity(quantity);
        item.setOriginalPrice(price);
        item.setDiscount(discount);
        return item;
    }

    private Invoice invoice(Long id, String number) {
        Invoice invoice = new Invoice();
        invoice.setId(id);
        invoice.setInvoiceNumber(number);
        invoice.setStatus(InvoiceStatus.ACTIVE);
        return invoice;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseData(ActionResponse response) {
        return (Map<String, Object>) response.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> responseItems(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }
}
