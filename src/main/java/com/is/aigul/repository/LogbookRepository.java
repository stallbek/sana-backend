package com.is.aigul.repository;

import com.is.aigul.model.Logbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LogbookRepository extends JpaRepository<Logbook, Long>, JpaSpecificationExecutor<Logbook> {

    @Override
    @EntityGraph(attributePaths = {"product", "productLocation"})
    Page<Logbook> findAll(@Nullable Specification<Logbook> specification, Pageable pageable);

    @EntityGraph(attributePaths = {"product", "productLocation"})
    List<Logbook> findByUserIdOrderByTimestampDescIdDesc(Long userId);

    @EntityGraph(attributePaths = {"product", "productLocation"})
    List<Logbook> findByActionTypeIgnoreCaseAndUserIdOrderByTimestampDescIdDesc(
            String actionType,
            Long userId
    );

    @EntityGraph(attributePaths = {"product", "productLocation"})
    List<Logbook> findByProductLocation_Product_NameIgnoreCaseAndUserIdOrderByTimestampDescIdDesc(
            String productName,
            Long userId
    );

    @EntityGraph(attributePaths = {"product", "productLocation"})
    List<Logbook> findByProductLocation_LocationNameIgnoreCaseAndUserIdOrderByTimestampDescIdDesc(
            String locationName,
            Long userId
    );

    @EntityGraph(attributePaths = {"product", "productLocation"})
    List<Logbook> findByTimestampBetweenAndUserIdOrderByTimestampDescIdDesc(
            LocalDateTime start,
            LocalDateTime end,
            Long userId
    );
}
