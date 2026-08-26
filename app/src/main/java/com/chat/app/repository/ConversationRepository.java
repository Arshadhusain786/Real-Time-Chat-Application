package com.chat.app.repository;

import com.chat.app.model.Conversation;
import com.chat.app.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    @Query("SELECT DISTINCT c FROM Conversation c JOIN c.members m WHERE m.user = :user ORDER BY c.updatedAt DESC")
    List<Conversation> findByMembersUserOrderByUpdatedAtDesc(@Param("user") User user);

    @Query("SELECT c FROM Conversation c JOIN c.members m1 JOIN c.members m2 " +
           "WHERE c.type = 'DIRECT' AND m1.user.id = :user1Id AND m2.user.id = :user2Id")
    List<Conversation> findDirectConversation(@Param("user1Id") Long user1Id, @Param("user2Id") Long user2Id);

    @Query("SELECT c FROM Conversation c WHERE c.disappearAfterMinutes IS NOT NULL AND c.disappearAfterMinutes > 0")
    List<Conversation> findConversationsWithDisappearing();
}
