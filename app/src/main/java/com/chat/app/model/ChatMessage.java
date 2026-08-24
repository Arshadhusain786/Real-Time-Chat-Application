package com.chat.app.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"sender", "conversation", "replyTo"})
@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_msg_conv_sent", columnList = "conversation_id, sent_at"),
    @Index(name = "idx_msg_sender", columnList = "sender_id"),
    @Index(name = "idx_msg_conv_read", columnList = "conversation_id, is_read")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String message;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "message_type", length = 20)
    @Builder.Default
    private String messageType = "TEXT";

    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @Column(name = "attachment_name")
    private String attachmentName;

    @Column(name = "attachment_size")
    private Long attachmentSize;

    // Reply support
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_id")
    private ChatMessage replyTo;

    // Edit/Delete support
    @Column(name = "is_edited")
    @Builder.Default
    private Boolean isEdited = false;

    @Column(name = "is_deleted")
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    // Delivery status: SENT, DELIVERED, READ
    @Column(name = "delivery_status", length = 10)
    @Builder.Default
    private String deliveryStatus = "SENT";

    @PrePersist
    protected void onCreate() {
        sentAt = LocalDateTime.now();
        if (isRead == null) isRead = false;
        if (isEdited == null) isEdited = false;
        if (isDeleted == null) isDeleted = false;
        if (deliveryStatus == null) deliveryStatus = "SENT";
    }

    public ChatMessageDTO toDTO() {
        Long sId = null;
        String sName = null;
        Long conversationId = this.conversation != null ? this.conversation.getId() : null;
        if (this.sender != null) {
            sId = this.sender.getId();
            sName = this.sender.getDisplayName() != null ? this.sender.getDisplayName() : this.sender.getUsername();
        }

        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder()
                .id(this.id)
                .message(this.isDeleted != null && this.isDeleted ? "This message was deleted" : this.message)
                .conversationId(conversationId)
                .senderId(sId)
                .senderName(sName)
                .sentAt(this.sentAt)
                .isRead(this.isRead)
                .messageType(this.messageType)
                .attachmentUrl(this.attachmentUrl)
                .attachmentName(this.attachmentName)
                .attachmentSize(this.attachmentSize)
                .isEdited(this.isEdited)
                .isDeleted(this.isDeleted)
                .deliveryStatus(this.deliveryStatus);

        if (this.replyTo != null) {
            builder.replyToId(this.replyTo.getId());
            builder.replyToMessage(this.replyTo.getIsDeleted() != null && this.replyTo.getIsDeleted()
                    ? "This message was deleted"
                    : this.replyTo.getMessage());
            builder.replyToSenderName(this.replyTo.getSender() != null
                    ? (this.replyTo.getSender().getDisplayName() != null
                        ? this.replyTo.getSender().getDisplayName()
                        : this.replyTo.getSender().getUsername())
                    : null);
        }

        return builder.build();
    }
}
