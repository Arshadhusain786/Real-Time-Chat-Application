package com.chat.app.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {

    private Long id;
    private String message;

    @JsonProperty("sender_id")
    private Long senderId;

    @JsonProperty("sender_name")
    private String senderName;

    @JsonProperty("conversation_id")
    private Long conversationId;

    @JsonProperty("sent_at")
    private LocalDateTime sentAt;

    @JsonProperty("is_read")
    private Boolean isRead;

    @JsonProperty("message_type")
    private String messageType;

    @JsonProperty("attachment_url")
    private String attachmentUrl;

    @JsonProperty("attachment_name")
    private String attachmentName;

    @JsonProperty("attachment_size")
    private Long attachmentSize;

    // Typing indicator
    @JsonProperty("typing")
    private Boolean typing;

    // Reply support
    @JsonProperty("reply_to_id")
    private Long replyToId;

    @JsonProperty("reply_to_message")
    private String replyToMessage;

    @JsonProperty("reply_to_sender_name")
    private String replyToSenderName;

    // Edit/Delete
    @JsonProperty("is_edited")
    private Boolean isEdited;

    @JsonProperty("is_deleted")
    private Boolean isDeleted;

    // Delivery status
    @JsonProperty("delivery_status")
    private String deliveryStatus;

    // Emoji reaction
    @JsonProperty("reaction")
    private String reaction;

    // Action type for special messages (edit, delete, reaction)
    @JsonProperty("action")
    private String action;
}
