package com.is.aigul.repository;
import com.is.aigul.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface ProductRepository extends JpaRepository<Product, Long> {
    @Query("SELECT p FROM Product p WHERE p.userId = :userId AND p.deletedAt IS NULL")
    List<Product> findByUserId(Long userId);

    @Query("""
            SELECT p
            FROM Product p
            WHERE LOWER(p.name) = LOWER(:name)
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    List<Product> findByNameIgnoreCaseAndUserId(String name, Long userId);

    @Query("""
            SELECT p
            FROM Product p
            WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    List<Product> findByNameContainingIgnoreCaseAndUserId(String name, Long userId);

    @Query("""
            SELECT DISTINCT p
            FROM Product p
            JOIN p.locations l
            WHERE LOWER(l.locationName) = LOWER(:location)
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    List<Product> findByLocations_LocationNameIgnoreCaseAndUserId(String location, Long userId);

    @Query("""
            SELECT p
            FROM Product p
            WHERE p.price BETWEEN :minPrice AND :maxPrice
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    List<Product> findByPriceBetweenAndUserId(Double minPrice, Double maxPrice, Long userId);

    @Query("""
            SELECT DISTINCT p
            FROM Product p
            JOIN p.locations l
            WHERE l.quantity BETWEEN :minQty AND :maxQty
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    List<Product> findByLocations_QuantityBetweenAndUserId(Integer minQty, Integer maxQty, Long userId);

    @Query("""
            SELECT p
            FROM Product p
            WHERE p.id = :id
              AND p.userId = :userId
              AND p.deletedAt IS NULL
            """)
    Optional<Product> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p JOIN p.locations l " +
            "WHERE LOWER(p.name) = LOWER(:name) AND LOWER(l.locationName) = LOWER(:location) " +
            "AND p.userId = :userId AND p.deletedAt IS NULL")
    Optional<Product> findByNameAndLocationAndUserId(@Param("name") String name, @Param("location") String location, @Param("userId") Long userId);

    @Query("SELECT p.name FROM Product p WHERE p.userId = :userId AND p.deletedAt IS NULL")
    List<String> findAllNamesByUserId(@Param("userId") Long userId);

    @Query("""
            SELECT p.id AS id, p.name AS name
            FROM Product p
            WHERE p.userId = :userId
              AND p.deletedAt IS NULL
              AND LOWER(SUBSTRING(p.name, 1, 1)) IN :initials
            ORDER BY LOWER(p.name), p.id
            """)
    List<ProductNameProjection> findSuggestionCandidates(
            @Param("userId") Long userId,
            @Param("initials") Collection<String> initials,
            Pageable pageable
    );

}
