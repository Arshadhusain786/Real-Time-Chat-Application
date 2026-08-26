package com.chat.app.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"conversation", "user"})
@Entity
@Table(name = "conversation_members", indexes = {
    @Index(name = "idx_cm_conv_user", columnList = "conversation_id, user_id"),
    @Index(name = "idx_cm_user", columnList = "user_id")
}, uniqueConstraints = {
    @UniqueConstraint(columnNames = {"conversation_id", "user_id"})
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMember {

    public enum Role {
        MEMBER, ADMIN, OWNER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Role role = Role.MEMBER;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;

    @Column(name = "is_pinned")
    @Builder.Default
    private Boolean isPinned = false;

    @Column(name = "is_muted")
    @Builder.Default
    private Boolean isMuted = false;

    @PrePersist
    protected void onJoin() {
        this.joinedAt = LocalDateTime.now();
    }
}
