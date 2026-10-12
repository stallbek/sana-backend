package com.is.aigul.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String username;

    @JsonIgnore
    @Column(name = "password", nullable = false)
    private String passwordHash;

    @JsonIgnore
    @Column(length = 254)
    private String email;

    @JsonIgnore
    @Column(length = 16)
    private String phone;

    @Column(nullable = false)
    private String role;

    @Column
    private String defaultLocation;

    @Column
    private String name;
}
