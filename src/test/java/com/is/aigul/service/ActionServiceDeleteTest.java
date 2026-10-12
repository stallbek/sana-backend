package com.is.aigul.service;

import com.is.aigul.dto.ActionResponse;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.exception.ProductNotFoundException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionServiceDeleteTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductLocationRepository productLocationRepository;
    @Mock
    private LogbookService logbookService;
    @Mock
    private InvoiceService invoiceService;

    private ActionService actionService;

    @BeforeEach
    void setUp() {
        actionService = new ActionService(
                productRepository,
                productLocationRepository,
                logbookService,
                invoiceService
        );
    }

    @Test
    void apiDeleteArchivesNonzeroStockPreservesLocationsAndLogsHistory() {
        Product product = product(42L, "Atlas", 9);
        ProductLocation location = product.getLocations().get(0);
        when(productRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.of(product));

        ActionResponse response = actionService.delete(42L, USER_ID);

        assertThat(product.getDeletedAt()).isNotNull();
        assertThat(location.getQuantity()).isEqualTo(9);
        assertThat(product.getLocations()).containsExactly(location);
        assertThat(response.getType()).isEqualTo("PRODUCT_DELETED");
        assertThat(responseData(response))
                .containsEntry("productId", 42L)
                .containsEntry("productName", "Atlas");
        verify(productRepository).save(product);
        verify(productRepository, never()).delete(product);
        verify(productLocationRepository, never()).delete(location);
        verify(logbookService).logAction(
                eq("DELETE"),
                eq("System"),
                isNull(),
                eq(0),
                eq(0.0),
                contains("Deleted product 'Atlas'"),
                eq(product),
                eq(USER_ID)
        );
    }

    @Test
    void commandDeleteUsesTheSameArchiveOperationAndResponseShape() {
        Product product = product(42L, "Atlas", 4);
        when(productRepository.findByNameIgnoreCaseAndUserId("atlas", USER_ID))
                .thenReturn(List.of(product));
        CommandDTO command = new CommandDTO();
        command.setProductName("atlas");

        ActionResponse response = actionService.delete(command, USER_ID);

        assertThat(product.getDeletedAt()).isNotNull();
        assertThat(response.getType()).isEqualTo("PRODUCT_DELETED");
        assertThat(responseData(response))
                .containsEntry("productId", 42L)
                .containsEntry("productName", "Atlas");
        verify(productRepository).save(product);
        verify(productRepository, never()).delete(product);
    }

    @Test
    void apiDeleteFailsClosedWhenProductIsMissingArchivedOrOwnedByAnotherUser() {
        when(productRepository.findByIdAndUserId(42L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> actionService.delete(42L, USER_ID))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Active product not found");

        verify(productRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(logbookService, never()).logAction(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseData(ActionResponse response) {
        return (Map<String, Object>) response.getData();
    }

    private Product product(Long id, String name, int quantity) {
        Product product = new Product();
        product.setId(id);
        product.setUserId(USER_ID);
        product.setName(name);
        product.setPrice(100.0);
        product.setLocations(new ArrayList<>());

        ProductLocation location = new ProductLocation();
        location.setId(11L);
        location.setUserId(USER_ID);
        location.setProduct(product);
        location.setLocationName("Bishkek");
        location.setQuantity(quantity);
        product.getLocations().add(location);
        return product;
    }
}
