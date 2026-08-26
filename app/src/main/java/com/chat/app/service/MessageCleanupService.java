package com.chat.app.service;

import com.chat.app.model.Conversation;
import com.chat.app.repository.ChatMessageRepository;
import com.chat.app.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MessageCleanupService {

    private final ConversationRepository conversationRepo;
    private final ChatMessageRepository messageRepo;

    /**
     * Every 5 minutes, delete messages older than the conversation's disappear timer.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    @Transactional
    public void cleanupDisappearingMessages() {
        List<Conversation> conversations = conversationRepo.findConversationsWithDisappearing();
        int totalDeleted = 0;
        for (Conversation conv : conversations) {
            if (conv.getDisappearAfterMinutes() != null && conv.getDisappearAfterMinutes() > 0) {
                LocalDateTime cutoff = LocalDateTime.now().minusMinutes(conv.getDisappearAfterMinutes());
                int deleted = messageRepo.deleteExpiredMessages(conv.getId(), cutoff);
                totalDeleted += deleted;
            }
        }
        if (totalDeleted > 0) {
            log.info("Disappearing messages cleanup: deleted {} messages", totalDeleted);
        }
    }
}
