package com.is.aigul.repository;

import com.is.aigul.model.Product;
import com.is.aigul.model.ProductLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductLocationRepository extends JpaRepository<ProductLocation, Long> {
    @Query("""
            SELECT pl
            FROM ProductLocation pl
            JOIN pl.product p
            WHERE pl.userId = :userId
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    List<ProductLocation> findByUserId(Long userId);

    @Query("""
            SELECT pl
            FROM ProductLocation pl
            JOIN pl.product p
            WHERE pl.product = :product
              AND pl.userId = :userId
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    List<ProductLocation> findByProductAndUserId(Product product, Long userId);

    @Query("""
            SELECT pl
            FROM ProductLocation pl
            JOIN pl.product p
            WHERE pl.product = :product
              AND LOWER(pl.locationName) = LOWER(:locationName)
              AND pl.userId = :userId
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    Optional<ProductLocation> findByProductAndLocationNameIgnoreCaseAndUserId(Product product, String locationName, Long userId);

    @Query("""
            SELECT DISTINCT pl.locationName
            FROM ProductLocation pl
            JOIN pl.product p
            WHERE pl.userId = :userId
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    List<String> findDistinctLocationNamesByUserId(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ProductLocation pl
            SET pl.locationName = :newName
            WHERE pl.userId = :userId
              AND LOWER(pl.locationName) = LOWER(:oldName)
              AND pl.product.id IN (
                  SELECT p.id FROM Product p WHERE p.deletedAt IS NULL
              )
            """)
    int renameLocationForUser(
            @Param("oldName") String oldName,
            @Param("newName") String newName,
            @Param("userId") Long userId
    );

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT pl FROM ProductLocation pl JOIN FETCH pl.product p
            WHERE pl.id = :id AND pl.userId = :userId AND p.userId = :userId AND p.deletedAt IS NULL
            """)
    Optional<ProductLocation> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
