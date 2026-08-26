package com.chat.app.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"messages", "members"})
@Entity
@Table(name = "conversations", indexes = {
    @Index(name = "idx_conv_type", columnList = "type"),
    @Index(name = "idx_conv_updated", columnList = "updated_at")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    public enum ConversationType {
        DIRECT, GROUP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100)
    private String name;
    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ConversationType type = ConversationType.DIRECT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "disappear_after_minutes")
    private Integer disappearAfterMinutes;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<ConversationMember> members = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Convert to DTO for a specific user context (to calculate unread, display name, etc.)
     */
    public ConversationDTO toDTO(User currentUser) {
        String displayName = this.name;
        String displayAvatar = this.avatarUrl;

        if (this.type == ConversationType.DIRECT && members != null && members.size() == 2) {
            for (ConversationMember member : members) {
                if (!member.getUser().getId().equals(currentUser.getId())) {
                    User otherUser = member.getUser();
                    displayName = otherUser.getDisplayName() != null ? otherUser.getDisplayName() : otherUser.getUsername();
                    displayAvatar = otherUser.getProfilePicture();
                    break;
                }
            }
        }

        return ConversationDTO.builder()
                .id(this.id)
                .name(displayName)
                .avatarUrl(displayAvatar)
                .description(this.description)
                .type(this.type != null ? this.type.name() : "DIRECT")
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .memberCount(this.members != null ? this.members.size() : 0)
                .build();
    }
}
