package com.is.aigul.repository;

import com.is.aigul.model.LocationNames;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface LocationNamesRepository extends JpaRepository<LocationNames, Long> {
    List<LocationNames> findByUserId(Long userId);

    Optional<LocationNames> findFirstByUserIdOrderByIdAsc(Long userId);

    Optional<LocationNames> findByLocationNameIgnoreCaseAndUserId(String locationName, Long userId);
}
