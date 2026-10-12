package com.is.aigul.repository;

import com.is.aigul.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {
    @Override
    @EntityGraph(attributePaths = "customer")
    Page<Invoice> findAll(Specification<Invoice> specification, Pageable pageable);

    Optional<Invoice> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id and i.userId = :userId")
    Optional<Invoice> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);
    boolean existsByInvoiceNumber(String invoiceNumber);

    @Query("""
            select i.customer.id as customerId,
                   coalesce(sum(i.totalAmount), 0.0) as totalOwed,
                   count(i.id) as assignedInvoiceCount
            from Invoice i
            where i.userId = :userId
              and i.customer.id in :customerIds
              and i.operation = com.is.aigul.model.InvoiceOperation.SELL
              and i.status = com.is.aigul.model.InvoiceStatus.ACTIVE
            group by i.customer.id
            """)
    List<CustomerDebtSummary> summarizeActiveSellAssignments(
            @Param("userId") Long userId,
            @Param("customerIds") Collection<Long> customerIds
    );
}
