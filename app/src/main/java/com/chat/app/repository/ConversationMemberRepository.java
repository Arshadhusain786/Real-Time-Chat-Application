package com.chat.app.repository;

import com.chat.app.model.ConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, Long> {

    @Query("SELECT cm FROM ConversationMember cm WHERE cm.conversation.id = :conversationId AND cm.user.id = :userId")
    Optional<ConversationMember> findByConversationIdAndUserId(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId);

    boolean existsByConversationIdAndUserId(Long conversationId, Long userId);
}
