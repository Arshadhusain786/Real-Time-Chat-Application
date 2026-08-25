package com.chat.app.repository;

import com.chat.app.model.BlockedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BlockedUserRepository extends JpaRepository<BlockedUser, Long> {

    Optional<BlockedUser> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    @Query("SELECT b FROM BlockedUser b WHERE b.blocker.id = :userId")
    List<BlockedUser> findAllByBlockerId(@Param("userId") Long userId);

    void deleteByBlockerIdAndBlockedId(Long blockerId, Long blockedId);
}
