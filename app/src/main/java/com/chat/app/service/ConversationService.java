package com.chat.app.service;

import com.chat.app.model.*;
import com.chat.app.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final UserService userService;

    @Transactional
    public Conversation createGroupConversation(String name, String description, Long creatorId, List<Long> memberIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }

        User creator = userService.getUserById(creatorId);

        Conversation conv = Conversation.builder()
                .name(name.trim())
                .description(description)
                .type(Conversation.ConversationType.GROUP)
                .build();
        conv = conversationRepo.save(conv);

        // Add creator as OWNER
        memberRepo.save(ConversationMember.builder()
                .conversation(conv)
                .user(creator)
                .role(ConversationMember.Role.OWNER)
                .build());

        // Add other members
        for (Long uid : memberIds) {
            if (!uid.equals(creatorId)) {
                User member = userService.getUserById(uid);
                memberRepo.save(ConversationMember.builder()
                        .conversation(conv)
                        .user(member)
                        .role(ConversationMember.Role.MEMBER)
                        .build());
            }
        }

        log.info("Group conversation created: '{}' (ID: {}) by user {}", name, conv.getId(), creatorId);
        return conversationRepo.findById(conv.getId()).orElse(conv);
    }

    @Transactional(readOnly = true)
    public Conversation getConversationById(Long id) {
        return conversationRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
    }

    @Transactional(readOnly = true)
    public List<Conversation> getUserConversations(Long userId) {
        User user = userService.getUserById(userId);
        return conversationRepo.findByMembersUserOrderByUpdatedAtDesc(user);
    }

    @Transactional
    public Conversation getOrCreateDirectConversation(Long user1Id, Long user2Id) {
        if (user1Id.equals(user2Id)) {
            throw new IllegalArgumentException("Cannot create conversation with yourself");
        }

        // Check existing direct conversation
        List<Conversation> existing = conversationRepo.findDirectConversation(user1Id, user2Id);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        User user1 = userService.getUserById(user1Id);
        User user2 = userService.getUserById(user2Id);

        Conversation conv = Conversation.builder()
                .type(Conversation.ConversationType.DIRECT)
                .build();
        conv = conversationRepo.save(conv);

        memberRepo.save(ConversationMember.builder().conversation(conv).user(user1).role(ConversationMember.Role.MEMBER).build());
        memberRepo.save(ConversationMember.builder().conversation(conv).user(user2).role(ConversationMember.Role.MEMBER).build());

        log.info("Direct conversation created (ID: {}) between {} and {}", conv.getId(), user1Id, user2Id);
        return conversationRepo.findById(conv.getId()).orElse(conv);
    }

    /**
     * Verify user is a member of the conversation. Throws if not.
     */
    @Transactional(readOnly = true)
    public ConversationMember verifyMembership(Long conversationId, Long userId) {
        return memberRepo.findByConversationIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new SecurityException("User is not a member of this conversation"));
    }

    @Transactional(readOnly = true)
    public boolean isMember(Long conversationId, Long userId) {
        return memberRepo.existsByConversationIdAndUserId(conversationId, userId);
    }

    @Transactional
    public void addMember(Long conversationId, Long userId, Long requesterId) {
        Conversation conv = getConversationById(conversationId);
        if (conv.getType() != Conversation.ConversationType.GROUP) {
            throw new IllegalArgumentException("Cannot add members to a direct conversation");
        }

        ConversationMember requester = verifyMembership(conversationId, requesterId);
        if (requester.getRole() == ConversationMember.Role.MEMBER) {
            throw new SecurityException("Only admins or owners can add members");
        }

        if (isMember(conversationId, userId)) {
            throw new IllegalArgumentException("User is already a member");
        }

        User newMember = userService.getUserById(userId);
        memberRepo.save(ConversationMember.builder()
                .conversation(conv)
                .user(newMember)
                .role(ConversationMember.Role.MEMBER)
                .build());
    }

    @Transactional
    public void removeMember(Long conversationId, Long userId, Long requesterId) {
        Conversation conv = getConversationById(conversationId);
        if (conv.getType() != Conversation.ConversationType.GROUP) {
            throw new IllegalArgumentException("Cannot remove members from a direct conversation");
        }

        ConversationMember requester = verifyMembership(conversationId, requesterId);
        if (requester.getRole() == ConversationMember.Role.MEMBER && !userId.equals(requesterId)) {
            throw new SecurityException("Only admins or owners can remove members");
        }

        ConversationMember toRemove = verifyMembership(conversationId, userId);
        if (toRemove.getRole() == ConversationMember.Role.OWNER && !userId.equals(requesterId)) {
            throw new SecurityException("Cannot remove the owner");
        }

        memberRepo.delete(toRemove);
    }

    @Transactional
    public void updateGroupInfo(Long conversationId, Long requesterId, String name, String description, String avatarUrl) {
        Conversation conv = getConversationById(conversationId);
        if (conv.getType() != Conversation.ConversationType.GROUP) {
            throw new IllegalArgumentException("Not a group conversation");
        }

        ConversationMember requester = verifyMembership(conversationId, requesterId);
        if (requester.getRole() == ConversationMember.Role.MEMBER) {
            throw new SecurityException("Only admins or owners can update group info");
        }

        if (name != null && !name.isBlank()) conv.setName(name.trim());
        if (description != null) conv.setDescription(description.trim());
        if (avatarUrl != null) conv.setAvatarUrl(avatarUrl);
        conversationRepo.save(conv);
    }

    @Transactional
    public void promoteToAdmin(Long conversationId, Long userId, Long requesterId) {
        ConversationMember requester = verifyMembership(conversationId, requesterId);
        if (requester.getRole() != ConversationMember.Role.OWNER) {
            throw new SecurityException("Only the owner can promote members");
        }
        ConversationMember target = verifyMembership(conversationId, userId);
        target.setRole(ConversationMember.Role.ADMIN);
        memberRepo.save(target);
    }
}
