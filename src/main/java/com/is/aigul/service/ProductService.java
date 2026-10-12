package com.is.aigul.service;

import com.is.aigul.dto.LocationDTO;
import com.is.aigul.model.Product;
import com.is.aigul.dto.ProductDTO;
import com.is.aigul.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    private ProductDTO toDTO(Product product) {

        List<LocationDTO> locs = product.getLocations()
                .stream()
                .filter(l -> java.util.Objects.equals(l.getUserId(), product.getUserId()))
                .map(l -> new LocationDTO(
                        l.getLocationName(),
                        l.getQuantity()
                ))
                .toList();

        int totalQty = locs.stream()
                .mapToInt(LocationDTO::getQuantity)
                .sum();

        return new ProductDTO(
                product.getId(),
                product.getName(),
                product.getPrice(),
                totalQty,
                locs
        );
    }


    public List<ProductDTO> filter(
            String name,
            String location,
            Double minPrice,
            Double maxPrice,
            Integer minQty,
            Integer maxQty,
            Long userId
    ) {

        List<Product> products = productRepository.findByUserId(userId);

        if (name != null) {
            products = products.stream()
                    .filter(p -> p.getName().toLowerCase().contains(name.toLowerCase()))
                    .toList();
        }

        if (location != null) {
            products = products.stream()
                    .filter(p -> p.getLocations().stream()
                            .anyMatch(l -> l.getLocationName().equalsIgnoreCase(location)))
                    .toList();
        }

        if (minPrice != null) {
            products = products.stream()
                    .filter(p -> p.getPrice() >= minPrice)
                    .toList();
        }

        if (maxPrice != null) {
            products = products.stream()
                    .filter(p -> p.getPrice() <= maxPrice)
                    .toList();
        }

        if (minQty != null) {
            products = products.stream()
                    .filter(p -> p.getLocations().stream()
                            .anyMatch(l -> l.getQuantity() >= minQty))
                    .toList();
        }

        if (maxQty != null) {
            products = products.stream()
                    .filter(p -> p.getLocations().stream()
                            .anyMatch(l -> l.getQuantity() <= maxQty))
                    .toList();
        }

        // Convert to DTO
        return products.stream()
                .map(this::toDTO)
                .toList();
    }
}
