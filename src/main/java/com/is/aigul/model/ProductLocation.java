package com.is.aigul.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "product_location")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductLocation {
    @Id @GeneratedValue
    private Long id;

    private Long userId;

    private String locationName;
    private int quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;
}
