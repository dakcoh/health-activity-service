package com.example.healthactivity.member;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "members")
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 50)
    private String nickname;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Member() {}

    public Member(String name, String nickname, String email, String passwordHash) {
        this.name = name;
        this.nickname = nickname;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNickname() { return nickname; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
}
