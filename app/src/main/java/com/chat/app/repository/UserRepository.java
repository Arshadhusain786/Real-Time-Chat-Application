package com.chat.app.repository;

import com.chat.app.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndIsActiveTrue(Long id);

    List<User> findAllByIsActiveTrueAndIdNotOrderByDisplayName(Long userId);

    @Query("SELECT u FROM User u WHERE u.isActive = true AND u.id != :userId AND " +
           "(LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<User> searchUsers(@Param("query") String query, @Param("userId") Long userId);
}
