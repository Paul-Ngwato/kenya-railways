package com.ueab.sac.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ueab.sac.model.User;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}
