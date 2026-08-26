package com.chat.app.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationDTO {

    private Long id;
    private String name;
    private String description;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    private String type;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    @JsonProperty("unread_count")
    private Integer unreadCount;

    @JsonProperty("last_message")
    private ChatMessageDTO lastMessage;

    @JsonProperty("member_count")
    private Integer memberCount;

    @JsonProperty("is_pinned")
    private Boolean isPinned;

    @JsonProperty("is_muted")
    private Boolean isMuted;

    private List<MemberDTO> members;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberDTO {
        private Long id;
        private String username;
        @JsonProperty("display_name")
        private String displayName;
        @JsonProperty("profile_picture")
        private String profilePicture;
        @JsonProperty("is_online")
        private Boolean isOnline;
        @JsonProperty("last_seen")
        private String lastSeen;
        private String role;
    }
}
