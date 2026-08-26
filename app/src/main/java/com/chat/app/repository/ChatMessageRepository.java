package com.chat.app.repository;

import com.chat.app.model.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    @Query("SELECT m FROM ChatMessage m WHERE m.conversation.id = :conversationId ORDER BY m.sentAt ASC")
    Page<ChatMessage> findByConversationId(@Param("conversationId") Long conversationId, Pageable pageable);

    @Query("SELECT m FROM ChatMessage m WHERE m.conversation.id = :conversationId AND m.isRead = false AND m.sender.id != :userId")
    List<ChatMessage> findUnreadMessagesForUser(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage m SET m.isRead = true, m.readAt = :readAt, m.deliveryStatus = 'READ' " +
           "WHERE m.conversation.id = :conversationId AND m.isRead = false AND m.sender.id != :userId")
    int markMessagesAsReadForUser(@Param("conversationId") Long conversationId,
                                  @Param("userId") Long userId,
                                  @Param("readAt") LocalDateTime readAt);

    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.conversation.id = :conversationId AND m.isRead = false AND m.sender.id != :userId")
    long countUnreadForUser(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Query("SELECT m FROM ChatMessage m WHERE m.conversation.id = :conversationId ORDER BY m.sentAt DESC LIMIT 1")
    ChatMessage findLatestMessage(@Param("conversationId") Long conversationId);

    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage m SET m.deliveryStatus = 'DELIVERED' " +
           "WHERE m.conversation.id = :conversationId AND m.sender.id != :userId AND m.deliveryStatus = 'SENT'")
    int markMessagesAsDelivered(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage m WHERE m.conversation.id = :conversationId")
    void deleteByConversationId(@Param("conversationId") Long conversationId);

    @Query("SELECT m FROM ChatMessage m WHERE m.conversation.id = :conversationId " +
           "AND LOWER(m.message) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY m.sentAt DESC")
    List<ChatMessage> searchMessages(@Param("conversationId") Long conversationId, @Param("query") String query);

    @Query("SELECT m FROM ChatMessage m JOIN m.conversation c JOIN c.members cm " +
           "WHERE cm.user.id = :userId AND LOWER(m.message) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "ORDER BY m.sentAt DESC")
    List<ChatMessage> searchAllUserMessages(@Param("userId") Long userId, @Param("query") String query, Pageable pageable);
}
