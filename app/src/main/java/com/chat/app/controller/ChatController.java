package com.chat.app.controller;

import com.chat.app.model.*;
import com.chat.app.service.ChatMessageService;
import com.chat.app.service.ConversationService;
import com.chat.app.service.PresenceService;
import com.chat.app.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final ChatMessageService chatMessageService;
    private final UserService userService;
    private final ConversationService conversationService;
    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * WebSocket: Send a message
     */
    @MessageMapping("/sendmessage")
    public void sendMessage(ChatMessageDTO messageDTO, Principal principal) {
        if (principal == null) {
            log.warn("Unauthorized WebSocket message attempt");
            return;
        }

        String senderUsername = principal.getName();
        log.debug("WebSocket message received from {} - convId={}, type={}, msg={}",
                senderUsername, messageDTO.getConversationId(), messageDTO.getMessageType(),
                messageDTO.getMessage() != null ? messageDTO.getMessage().substring(0, Math.min(50, messageDTO.getMessage().length())) : "null");

        User sender = (User) userService.loadUserByUsername(senderUsername);

        if (messageDTO.getConversationId() == null) {
            log.warn("Message from {} rejected: conversationId is null (DTO deserialization may have failed)", senderUsername);
            return;
        }

        // Verify membership
        Long convId = messageDTO.getConversationId();
        if (!conversationService.isMember(convId, sender.getId())) {
            log.warn("User {} (ID:{}) not a member of conversation {}", senderUsername, sender.getId(), convId);
            return;
        }

        Conversation conversation = conversationService.getConversationById(convId);

        try {
            ChatMessage chatMessage = chatMessageService.saveMessage(
                    sender.getId(),
                    convId,
                    messageDTO.getMessage(),
                    messageDTO.getMessageType(),
                    messageDTO.getAttachmentUrl(),
                    messageDTO.getAttachmentName(),
                    messageDTO.getAttachmentSize(),
                    messageDTO.getReplyToId()
            );

            ChatMessageDTO responseDTO = chatMessage.toDTO();

            // A1: Per-member try/catch so one failed delivery doesn't block others
            for (ConversationMember member : conversation.getMembers()) {
                String targetUsername = member.getUser().getUsername();
                try {
                    messagingTemplate.convertAndSendToUser(
                            targetUsername,
                            "/queue/messages",
                            responseDTO
                    );
                } catch (Exception deliveryEx) {
                    log.warn("Failed to deliver message {} to user {}: {}", chatMessage.getId(), targetUsername, deliveryEx.getMessage());
                }
            }

            // A1: Push conversation-updated event to all members so their sidebar updates
            // (unread badge, last message preview, conversation moves to top)
            ConversationDTO convUpdateDTO = buildConversationUpdateDTO(conversation, chatMessage);
            for (ConversationMember member : conversation.getMembers()) {
                try {
                    messagingTemplate.convertAndSendToUser(
                            member.getUser().getUsername(),
                            "/queue/conversations",
                            convUpdateDTO
                    );
                } catch (Exception ex) {
                    // Non-critical - sidebar update missed, will sync on next load
                }
            }

            log.debug("Message {} saved and delivered to {} members in conversation {}",
                    chatMessage.getId(), conversation.getMembers().size(), convId);
        } catch (Exception e) {
            log.error("Failed to save/deliver message from {} to conversation {}", senderUsername, convId, e);
        }
    }

    /**
     * WebSocket: Typing indicator
     */
    @MessageMapping("/typing")
    public void handleTyping(ChatMessageDTO typingDTO, Principal principal) {
        if (principal == null || typingDTO.getConversationId() == null) return;

        User sender = (User) userService.loadUserByUsername(principal.getName());
        Long convId = typingDTO.getConversationId();

        if (!conversationService.isMember(convId, sender.getId())) return;

        Conversation conversation = conversationService.getConversationById(convId);

        ChatMessageDTO typing = ChatMessageDTO.builder()
                .senderId(sender.getId())
                .senderName(sender.getDisplayName() != null ? sender.getDisplayName() : sender.getUsername())
                .conversationId(convId)
                .typing(Boolean.TRUE.equals(typingDTO.getTyping()))
                .build();

        for (ConversationMember member : conversation.getMembers()) {
            if (!member.getUser().getId().equals(sender.getId())) {
                messagingTemplate.convertAndSendToUser(
                        member.getUser().getUsername(),
                        "/queue/typing",
                        typing
                );
            }
        }
    }

    /**
     * WebSocket: Read receipt
     */
    @MessageMapping("/readreceipt")
    public void handleReadReceipt(ChatMessageDTO receiptDTO, Principal principal) {
        if (principal == null || receiptDTO.getConversationId() == null) return;

        User currentUser = (User) userService.loadUserByUsername(principal.getName());
        Long convId = receiptDTO.getConversationId();

        if (!conversationService.isMember(convId, currentUser.getId())) return;

        int markedCount = chatMessageService.markMessagesAsRead(convId, currentUser.getId());

        if (markedCount > 0) {
            Conversation conversation = conversationService.getConversationById(convId);
            ChatMessageDTO ack = ChatMessageDTO.builder()
                    .senderId(currentUser.getId())
                    .conversationId(convId)
                    .isRead(true)
                    .action("READ_ACK")
                    .build();

            for (ConversationMember member : conversation.getMembers()) {
                messagingTemplate.convertAndSendToUser(
                        member.getUser().getUsername(),
                        "/queue/read",
                        ack
                );
            }
        }
    }

    /**
     * WebSocket: Edit message
     */
    @MessageMapping("/editmessage")
    public void handleEditMessage(ChatMessageDTO dto, Principal principal) {
        if (principal == null || dto.getId() == null) return;

        User user = (User) userService.loadUserByUsername(principal.getName());

        try {
            ChatMessage edited = chatMessageService.editMessage(dto.getId(), user.getId(), dto.getMessage());
            ChatMessageDTO responseDTO = edited.toDTO();
            responseDTO.setAction("EDIT");

            Conversation conversation = conversationService.getConversationById(edited.getConversation().getId());
            for (ConversationMember member : conversation.getMembers()) {
                messagingTemplate.convertAndSendToUser(
                        member.getUser().getUsername(),
                        "/queue/messages",
                        responseDTO
                );
            }
        } catch (Exception e) {
            log.warn("Edit message failed: {}", e.getMessage());
        }
    }

    /**
     * WebSocket: Delete message
     */
    @MessageMapping("/deletemessage")
    public void handleDeleteMessage(ChatMessageDTO dto, Principal principal) {
        if (principal == null || dto.getId() == null) return;

        User user = (User) userService.loadUserByUsername(principal.getName());

        try {
            ChatMessage deleted = chatMessageService.deleteMessage(dto.getId(), user.getId());
            ChatMessageDTO responseDTO = deleted.toDTO();
            responseDTO.setAction("DELETE");

            Conversation conversation = conversationService.getConversationById(deleted.getConversation().getId());
            for (ConversationMember member : conversation.getMembers()) {
                messagingTemplate.convertAndSendToUser(
                        member.getUser().getUsername(),
                        "/queue/messages",
                        responseDTO
                );
            }
        } catch (Exception e) {
            log.warn("Delete message failed: {}", e.getMessage());
        }
    }

    /**
     * WebSocket: Add/remove reaction
     */
    @MessageMapping("/reaction")
    public void handleReaction(ChatMessageDTO dto, Principal principal) {
        if (principal == null || dto.getId() == null || dto.getReaction() == null) return;

        User user = (User) userService.loadUserByUsername(principal.getName());

        try {
            ChatMessage msg = chatMessageService.getLatestMessage(dto.getConversationId());
            // Get the actual message
            var reaction = chatMessageService.addReaction(dto.getId(), user.getId(), dto.getReaction());

            ChatMessageDTO responseDTO = ChatMessageDTO.builder()
                    .id(dto.getId())
                    .conversationId(dto.getConversationId())
                    .senderId(user.getId())
                    .senderName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername())
                    .reaction(dto.getReaction())
                    .action(reaction != null ? "REACTION_ADD" : "REACTION_REMOVE")
                    .build();

            Conversation conversation = conversationService.getConversationById(dto.getConversationId());
            for (ConversationMember member : conversation.getMembers()) {
                messagingTemplate.convertAndSendToUser(
                        member.getUser().getUsername(),
                        "/queue/messages",
                        responseDTO
                );
            }
        } catch (Exception e) {
            log.warn("Reaction failed: {}", e.getMessage());
        }
    }

    // === REST Endpoints ===

    @GetMapping("/api/messages/{conversationId}")
    @ResponseBody
    public ResponseEntity<?> getConversationHistory(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") int page) {

        User currentUser = getAuthenticatedUser();
        if (!conversationService.isMember(conversationId, currentUser.getId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }

        // Mark as delivered when fetching
        chatMessageService.markMessagesAsDelivered(conversationId, currentUser.getId());

        List<ChatMessage> messages = chatMessageService.getConversationHistory(conversationId, page);
        List<ChatMessageDTO> dtos = messages.stream().map(ChatMessage::toDTO).toList();

        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/api/messages/{conversationId}/read")
    @ResponseBody
    public ResponseEntity<?> markAsRead(@PathVariable Long conversationId) {
        User currentUser = getAuthenticatedUser();
        if (!conversationService.isMember(conversationId, currentUser.getId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }

        int count = chatMessageService.markMessagesAsRead(conversationId, currentUser.getId());
        return ResponseEntity.ok(Map.of("markedRead", count));
    }

    @GetMapping("/api/conversations")
    @ResponseBody
    public ResponseEntity<?> getUserConversations() {
        User currentUser = getAuthenticatedUser();

        List<Conversation> conversations = conversationService.getUserConversations(currentUser.getId());
        List<ConversationDTO> dtos = conversations.stream().map(conv -> {
            ConversationDTO dto = conv.toDTO(currentUser);
            // Calculate unread count from DB
            long unread = chatMessageService.getUnreadCount(conv.getId(), currentUser.getId());
            dto.setUnreadCount((int) unread);

            // Get latest message
            ChatMessage latest = chatMessageService.getLatestMessage(conv.getId());
            if (latest != null) {
                dto.setLastMessage(latest.toDTO());
            }

            // Add member info with online status
            List<ConversationDTO.MemberDTO> memberDtos = conv.getMembers().stream()
                    .map(m -> ConversationDTO.MemberDTO.builder()
                            .id(m.getUser().getId())
                            .username(m.getUser().getUsername())
                            .displayName(m.getUser().getDisplayName())
                            .profilePicture(m.getUser().getProfilePicture())
                            .isOnline(presenceService.isUserOnline(m.getUser().getId()))
                            .role(m.getRole().name())
                            .build())
                    .collect(Collectors.toList());
            dto.setMembers(memberDtos);

            return dto;
        }).toList();

        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/api/conversations/direct/{userId}")
    @ResponseBody
    public ResponseEntity<?> getOrCreateDirectConversation(@PathVariable Long userId) {
        User currentUser = getAuthenticatedUser();
        try {
            boolean isNew = !conversationService.hasDirectConversation(currentUser.getId(), userId);
            Conversation conversation = conversationService.getOrCreateDirectConversation(currentUser.getId(), userId);
            ConversationDTO dto = conversation.toDTO(currentUser);
            long unread = chatMessageService.getUnreadCount(conversation.getId(), currentUser.getId());
            dto.setUnreadCount((int) unread);

            List<ConversationDTO.MemberDTO> memberDtos = conversation.getMembers().stream()
                    .map(m -> ConversationDTO.MemberDTO.builder()
                            .id(m.getUser().getId())
                            .username(m.getUser().getUsername())
                            .displayName(m.getUser().getDisplayName())
                            .profilePicture(m.getUser().getProfilePicture())
                            .isOnline(presenceService.isUserOnline(m.getUser().getId()))
                            .role(m.getRole().name())
                            .build())
                    .collect(Collectors.toList());
            dto.setMembers(memberDtos);

            // A2: If this is a brand-new conversation, notify the OTHER user
            if (isNew) {
                User otherUser = userService.getUserById(userId);
                // Build DTO from the other user's perspective
                ConversationDTO otherDto = conversation.toDTO(otherUser);
                otherDto.setUnreadCount(0);
                otherDto.setMembers(memberDtos);
                try {
                    messagingTemplate.convertAndSendToUser(
                            otherUser.getUsername(),
                            "/queue/conversations",
                            otherDto
                    );
                } catch (Exception ex) {
                    log.debug("Could not push new DM notification to {}: {}", otherUser.getUsername(), ex.getMessage());
                }
            }

            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/conversations/group")
    @ResponseBody
    public ResponseEntity<?> createGroupConversation(@RequestBody Map<String, Object> request) {
        User currentUser = getAuthenticatedUser();
        String name = (String) request.get("name");
        String description = (String) request.get("description");
        @SuppressWarnings("unchecked")
        List<Integer> memberIdsRaw = (List<Integer>) request.get("memberIds");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Group name required"));
        }
        if (memberIdsRaw == null || memberIdsRaw.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one member required"));
        }

        List<Long> memberIds = memberIdsRaw.stream().map(Integer::longValue).collect(Collectors.toList());
        memberIds.add(currentUser.getId());

        try {
            Conversation conv = conversationService.createGroupConversation(name, description, currentUser.getId(), memberIds);
            ConversationDTO dto = conv.toDTO(currentUser);
            dto.setUnreadCount(0);

            List<ConversationDTO.MemberDTO> memberDtos = conv.getMembers().stream()
                    .map(m -> ConversationDTO.MemberDTO.builder()
                            .id(m.getUser().getId())
                            .username(m.getUser().getUsername())
                            .displayName(m.getUser().getDisplayName())
                            .profilePicture(m.getUser().getProfilePicture())
                            .isOnline(presenceService.isUserOnline(m.getUser().getId()))
                            .role(m.getRole().name())
                            .build())
                    .collect(Collectors.toList());
            dto.setMembers(memberDtos);

            // Notify all members about the new group
            for (ConversationMember member : conv.getMembers()) {
                messagingTemplate.convertAndSendToUser(
                        member.getUser().getUsername(),
                        "/queue/conversations",
                        dto
                );
            }

            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/conversations/{conversationId}/members/{userId}")
    @ResponseBody
    public ResponseEntity<?> addMember(@PathVariable Long conversationId, @PathVariable Long userId) {
        User currentUser = getAuthenticatedUser();
        try {
            conversationService.addMember(conversationId, userId, currentUser.getId());
            return ResponseEntity.ok(Map.of("message", "Member added"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/conversations/{conversationId}/members/{userId}")
    @ResponseBody
    public ResponseEntity<?> removeMember(@PathVariable Long conversationId, @PathVariable Long userId) {
        User currentUser = getAuthenticatedUser();
        try {
            conversationService.removeMember(conversationId, userId, currentUser.getId());
            return ResponseEntity.ok(Map.of("message", "Member removed"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/conversations/{conversationId}")
    @ResponseBody
    public ResponseEntity<?> updateGroup(@PathVariable Long conversationId, @RequestBody Map<String, String> request) {
        User currentUser = getAuthenticatedUser();
        try {
            conversationService.updateGroupInfo(conversationId, currentUser.getId(),
                    request.get("name"), request.get("description"), request.get("avatarUrl"));
            return ResponseEntity.ok(Map.of("message", "Group updated"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/unread-counts")
    @ResponseBody
    public ResponseEntity<?> getUnreadCounts() {
        User currentUser = getAuthenticatedUser();
        List<Conversation> conversations = conversationService.getUserConversations(currentUser.getId());

        Map<Long, Long> counts = new HashMap<>();
        long total = 0;
        for (Conversation conv : conversations) {
            long count = chatMessageService.getUnreadCount(conv.getId(), currentUser.getId());
            if (count > 0) {
                counts.put(conv.getId(), count);
                total += count;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("counts", counts);
        result.put("total", total);
        return ResponseEntity.ok(result);
    }

    @GetMapping({"/", "/chat"})
    public String chat() {
        return "chat";
    }

    private User getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }

    /**
     * Build a lightweight conversation update DTO to push to members' sidebar.
     */
    private ConversationDTO buildConversationUpdateDTO(Conversation conversation, ChatMessage latestMessage) {
        ConversationDTO dto = ConversationDTO.builder()
                .id(conversation.getId())
                .name(conversation.getName())
                .avatarUrl(conversation.getAvatarUrl())
                .type(conversation.getType() != null ? conversation.getType().name() : "DIRECT")
                .updatedAt(conversation.getUpdatedAt())
                .lastMessage(latestMessage != null ? latestMessage.toDTO() : null)
                .memberCount(conversation.getMembers() != null ? conversation.getMembers().size() : 0)
                .build();

        if (conversation.getMembers() != null) {
            List<ConversationDTO.MemberDTO> memberDtos = conversation.getMembers().stream()
                    .map(m -> ConversationDTO.MemberDTO.builder()
                            .id(m.getUser().getId())
                            .username(m.getUser().getUsername())
                            .displayName(m.getUser().getDisplayName())
                            .profilePicture(m.getUser().getProfilePicture())
                            .isOnline(presenceService.isUserOnline(m.getUser().getId()))
                            .role(m.getRole().name())
                            .build())
                    .collect(Collectors.toList());
            dto.setMembers(memberDtos);
        }
        return dto;
    }
}
