package com.is.aigul.service;

import com.is.aigul.dto.CommandDTO;
import com.is.aigul.dto.SellItemDTO;
import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommandPreprocessorServiceTest {

    @Test
    void addFuzzyMatchesExistingProductForCurrentUser() {
        ProductMatchService productMatchService = mock(ProductMatchService.class);
        UserService userService = mock(UserService.class);
        CommandPreprocessorService service = new CommandPreprocessorService(productMatchService, userService);
        CommandDTO cmd = addCard("Atlass", "Osh");
        Product atlas = product("Atlas");

        when(productMatchService.findAllBestMatches("Atlass", 7L)).thenReturn(List.of(atlas));
        when(productMatchService.findClosestGlobalLocation("Osh", 70, 7L)).thenReturn("Osh");

        CommandDTO result = service.preprocess(cmd, 7L);

        assertThat(result.getItems().get(0).getProductName()).isEqualTo("Atlas");
        assertThat(result.getWarnings()).isNull();
    }

    @Test
    void addKeepsUnknownProductAndAddsWarning() {
        ProductMatchService productMatchService = mock(ProductMatchService.class);
        UserService userService = mock(UserService.class);
        CommandPreprocessorService service = new CommandPreprocessorService(productMatchService, userService);
        CommandDTO cmd = addCard("New Fabric", "Osh");

        when(productMatchService.findAllBestMatches("New Fabric", 7L)).thenReturn(List.of());
        when(productMatchService.findClosestGlobalLocation("Osh", 70, 7L)).thenReturn("Osh");

        CommandDTO result = service.preprocess(cmd, 7L);

        assertThat(result.getItems().get(0).getProductName()).isEqualTo("New Fabric");
        assertThat(result.getWarnings())
                .containsExactly("Product 'New Fabric' was not found. Create it first, or change this card to CREATE.");
    }

    @Test
    void addKeepsNewLocationWhenNoConfidentMatchExists() {
        ProductMatchService productMatchService = mock(ProductMatchService.class);
        UserService userService = mock(UserService.class);
        CommandPreprocessorService service = new CommandPreprocessorService(productMatchService, userService);
        CommandDTO cmd = addCard("Atlas", "New Warehouse");

        when(productMatchService.findAllBestMatches("Atlas", 7L)).thenReturn(List.of(product("Atlas")));
        when(productMatchService.findClosestGlobalLocation("New Warehouse", 70, 7L)).thenReturn(null);

        CommandDTO result = service.preprocess(cmd, 7L);

        assertThat(result.getItems().get(0).getLocation()).isEqualTo("New Warehouse");
    }

    @Test
    void addDefaultsBlankLocation() {
        ProductMatchService productMatchService = mock(ProductMatchService.class);
        UserService userService = mock(UserService.class);
        CommandPreprocessorService service = new CommandPreprocessorService(productMatchService, userService);
        CommandDTO cmd = addCard("Atlas", "");

        when(productMatchService.findAllBestMatches("Atlas", 7L)).thenReturn(List.of(product("Atlas")));
        when(userService.getDefaultLocation(7L)).thenReturn("Main");

        CommandDTO result = service.preprocess(cmd, 7L);

        assertThat(result.getItems().get(0).getLocation()).isEqualTo("Main");
        verify(productMatchService, never()).findClosestGlobalLocation("", 70, 7L);
    }

    @Test
    void sellInsufficientStockKeepsRequestedQuantityAndAddsWarning() {
        ProductMatchService productMatchService = mock(ProductMatchService.class);
        UserService userService = mock(UserService.class);
        CommandPreprocessorService service = new CommandPreprocessorService(productMatchService, userService);
        Product atlas = product("Atlas");
        location(atlas, "Osh", 2);
        CommandDTO cmd = sellCard("Atlass", "Osh", 5);

        when(productMatchService.findClosestProductNoThreshold("Atlass", 7L)).thenReturn(atlas);
        when(productMatchService.findClosestLocationNoThreshold(atlas, "Osh")).thenReturn("Osh");

        CommandDTO result = service.preprocess(cmd, 7L);

        assertThat(result.getItems().get(0).getProductName()).isEqualTo("Atlas");
        assertThat(result.getItems().get(0).getLocation()).isEqualTo("Osh");
        assertThat(result.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(result.getItems().get(0).getTotalPrice()).isEqualTo(0.0);
        assertThat(result.getWarnings()).containsExactly(
                "STOCK_SHORTAGE|product=Atlas|location=Osh|requested=5|available=2|remainingStock=0");
    }

    private CommandDTO addCard(String productName, String location) {
        SellItemDTO item = new SellItemDTO();
        item.setProductName(productName);
        item.setQuantity(5);
        item.setLocation(location);

        CommandDTO cmd = new CommandDTO();
        cmd.setOperation("ADD");
        cmd.setItems(List.of(item));
        return cmd;
    }

    private CommandDTO sellCard(String productName, String location, int quantity) {
        SellItemDTO item = new SellItemDTO();
        item.setProductName(productName);
        item.setQuantity(quantity);
        item.setLocation(location);

        CommandDTO cmd = new CommandDTO();
        cmd.setOperation("SELL");
        cmd.setItems(List.of(item));
        return cmd;
    }

    private Product product(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(0.0);
        product.setLocations(new ArrayList<>());
        return product;
    }

    private ProductLocation location(Product product, String name, int quantity) {
        ProductLocation location = new ProductLocation();
        location.setProduct(product);
        location.setLocationName(name);
        location.setQuantity(quantity);
        product.getLocations().add(location);
        return location;
    }
}
