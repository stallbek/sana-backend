package com.is.aigul.repository;

import com.is.aigul.model.CustomerPaymentEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerPaymentEntryRepository extends JpaRepository<CustomerPaymentEntry, Long> {
    @EntityGraph(attributePaths = {"customer", "reversalOfPayment"})
    Optional<CustomerPaymentEntry> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @EntityGraph(attributePaths = {"customer", "reversalOfPayment"})
    Optional<CustomerPaymentEntry> findByIdAndUserIdAndCustomer_Id(
            Long id, Long userId, Long customerId);

    boolean existsByReversalOfPayment_Id(Long paymentId);

    @EntityGraph(attributePaths = "reversalOfPayment")
    Page<CustomerPaymentEntry> findByUserIdAndCustomer_Id(
            Long userId, Long customerId, Pageable pageable);

    @Query("""
            select e.customer.id as customerId,
                   coalesce(sum(case
                       when e.type = com.is.aigul.model.CustomerPaymentEntryType.PAYMENT then e.amount
                       else -e.amount
                   end), 0) as netPaidAmount
            from CustomerPaymentEntry e
            where e.userId = :userId and e.customer.id in :customerIds
            group by e.customer.id
            """)
    List<CustomerPaymentBalanceSummary> summarizeNetPayments(
            @Param("userId") Long userId,
            @Param("customerIds") Collection<Long> customerIds);
}
