package com.is.aigul.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_payment_entry")
@Getter
@Setter
@NoArgsConstructor
@Immutable
public class CustomerPaymentEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private CustomerPaymentEntryType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 16)
    private String currency = "som";

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CustomerPaymentMethod method;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 500)
    private String note;

    @Column(length = 500)
    private String reversalReason;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_payment_id", unique = true)
    private CustomerPaymentEntry reversalOfPayment;

    @Column(nullable = false, length = 255)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestFingerprint;
}
