package com.is.aigul.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "logbook")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Logbook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Column(nullable = false)
    private String actionType;

//    // The user or system performing the action
//    private String performedBy;

    // Timestamp of the action
    @Column(nullable = false)
    private LocalDateTime timestamp;

    // How many items were affected
    private int quantity;

    // Total monetary amount involved (e.g., sale price)
    private Double amount;

    // Optional free-form notes
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_location_id", nullable = true)
    private ProductLocation productLocation;

    private Long customerId;

    @Column(length = 120)
    private String customerName;

    private Long paymentEntryId;

    @Column(length = 16)
    private String currency;
}
