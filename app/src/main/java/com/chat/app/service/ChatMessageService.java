package com.chat.app.service;

import com.chat.app.model.*;
import com.chat.app.repository.ChatMessageRepository;
import com.chat.app.repository.ConversationRepository;
import com.chat.app.repository.MessageReactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatMessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final MessageReactionRepository reactionRepository;
    private final UserService userService;

    @Transactional
    public ChatMessage saveMessage(Long senderId, Long conversationId, String message,
                                   String messageType, String attachmentUrl,
                                   String attachmentName, Long attachmentSize, Long replyToId) {
        User sender = userService.getUserById(senderId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));

        String sanitizedMessage = sanitizeInput(message);

        ChatMessage.ChatMessageBuilder builder = ChatMessage.builder()
                .sender(sender)
                .conversation(conversation)
                .message(sanitizedMessage)
                .messageType(messageType != null ? messageType : "TEXT")
                .attachmentUrl(attachmentUrl)
                .attachmentName(attachmentName)
                .attachmentSize(attachmentSize)
                .isRead(false)
                .deliveryStatus("SENT");

        if (replyToId != null) {
            messageRepository.findById(replyToId).ifPresent(builder::replyTo);
        }

        ChatMessage saved = messageRepository.save(builder.build());

        // Update conversation timestamp
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        return saved;
    }

    @Transactional
    public ChatMessage saveMessage(Long senderId, Long conversationId, String message, String messageType, String attachmentUrl) {
        return saveMessage(senderId, conversationId, message, messageType, attachmentUrl, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getConversationHistory(Long conversationId, int pageNumber) {
        // Fetch messages ordered by sentAt ASC, paginated
        return messageRepository.findByConversationId(conversationId,
                PageRequest.of(pageNumber, 50, Sort.by("sentAt").ascending())).getContent();
    }

    @Transactional
    public int markMessagesAsRead(Long conversationId, Long userId) {
        int count = messageRepository.markMessagesAsReadForUser(conversationId, userId, LocalDateTime.now());
        log.debug("Marked {} messages as read in conversation {} for user {}", count, conversationId, userId);
        return count;
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long conversationId, Long userId) {
        return messageRepository.countUnreadForUser(conversationId, userId);
    }

    @Transactional
    public ChatMessage editMessage(Long messageId, Long userId, String newContent) {
        ChatMessage msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));
        if (!msg.getSender().getId().equals(userId)) {
            throw new SecurityException("Can only edit your own messages");
        }
        if (msg.getIsDeleted()) {
            throw new IllegalStateException("Cannot edit deleted message");
        }
        msg.setMessage(sanitizeInput(newContent));
        msg.setIsEdited(true);
        msg.setEditedAt(LocalDateTime.now());
        return messageRepository.save(msg);
    }

    @Transactional
    public ChatMessage deleteMessage(Long messageId, Long userId) {
        ChatMessage msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));
        if (!msg.getSender().getId().equals(userId)) {
            throw new SecurityException("Can only delete your own messages");
        }
        msg.setIsDeleted(true);
        msg.setMessage(null);
        msg.setAttachmentUrl(null);
        return messageRepository.save(msg);
    }

    @Transactional
    public MessageReaction addReaction(Long messageId, Long userId, String emoji) {
        ChatMessage msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));
        User user = userService.getUserById(userId);

        // Check if reaction already exists (toggle off)
        var existing = reactionRepository.findByMessageIdAndUserIdAndEmoji(messageId, userId, emoji);
        if (existing.isPresent()) {
            reactionRepository.delete(existing.get());
            return null; // indicates removal
        }

        return reactionRepository.save(MessageReaction.builder()
                .message(msg)
                .user(user)
                .emoji(emoji)
                .build());
    }

    @Transactional(readOnly = true)
    public List<MessageReaction> getReactions(Long messageId) {
        return reactionRepository.findByMessageId(messageId);
    }

    @Transactional
    public int markMessagesAsDelivered(Long conversationId, Long userId) {
        return messageRepository.markMessagesAsDelivered(conversationId, userId);
    }

    @Transactional(readOnly = true)
    public ChatMessage getLatestMessage(Long conversationId) {
        return messageRepository.findLatestMessage(conversationId);
    }

    @Transactional
    public void clearConversation(Long conversationId) {
        messageRepository.deleteByConversationId(conversationId);
        log.info("All messages cleared from conversation {}", conversationId);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDTO> searchInConversation(Long conversationId, String query) {
        return messageRepository.searchMessages(conversationId, query).stream()
                .limit(50)
                .map(ChatMessage::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDTO> searchAllMessages(Long userId, String query) {
        return messageRepository.searchAllUserMessages(userId, query,
                org.springframework.data.domain.PageRequest.of(0, 50)).stream()
                .map(ChatMessage::toDTO)
                .toList();
    }

    @Transactional
    public ChatMessage forwardMessage(Long messageId, Long senderId, Long targetConversationId) {
        ChatMessage original = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));
        if (original.getIsDeleted()) throw new IllegalStateException("Cannot forward deleted message");

        User sender = userService.getUserById(senderId);
        Conversation target = conversationRepository.findById(targetConversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));

        String forwardedText = original.getMessage() != null ? original.getMessage() : "";

        ChatMessage forwarded = ChatMessage.builder()
                .sender(sender)
                .conversation(target)
                .message(forwardedText)
                .messageType(original.getMessageType())
                .attachmentUrl(original.getAttachmentUrl())
                .attachmentName(original.getAttachmentName())
                .attachmentSize(original.getAttachmentSize())
                .isRead(false)
                .deliveryStatus("SENT")
                .build();

        target.setUpdatedAt(java.time.LocalDateTime.now());
        conversationRepository.save(target);
        return messageRepository.save(forwarded);
    }

    private String sanitizeInput(String input) {
        if (input == null) return "";
        // Replace dangerous HTML characters
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;")
                .trim();
    }
}
