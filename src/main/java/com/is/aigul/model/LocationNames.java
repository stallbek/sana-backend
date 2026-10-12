package com.is.aigul.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "location_names",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"user_id", "location_name"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LocationNames {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "location_name", nullable = false)
    private String locationName;
}
