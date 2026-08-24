package com.chat.app.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Slf4j
public class PresenceService {

    // Maps userId -> Set of active WebSocket session IDs
    private final ConcurrentMap<Long, Set<String>> userSessions = new ConcurrentHashMap<>();

    // Maps sessionId -> userId
    private final ConcurrentMap<String, Long> sessionToUser = new ConcurrentHashMap<>();

    @Autowired
    @Lazy
    private UserService userService;

    @Autowired
    @Lazy
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Register a new active WebSocket session for a user.
     * Only transitions user to ONLINE if this is their first active session.
     */
    public synchronized void addSession(Long userId, String username, String sessionId) {
        if (userId == null || sessionId == null) return;

        sessionToUser.put(sessionId, userId);
        Set<String> sessions = userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        boolean wasOffline = sessions.isEmpty();
        sessions.add(sessionId);

        if (wasOffline) {
            log.info("User {} (ID: {}) ONLINE via session {}", username, userId, sessionId);
            try {
                userService.updateUserStatus(userId, true, null);
            } catch (Exception e) {
                log.error("Failed to update status for user {}", userId, e);
            }
            broadcastPresence(userId, username, true, null);
        } else {
            log.debug("User {} added session {} (total: {})", username, sessionId, sessions.size());
        }
    }

    /**
     * Unregister a WebSocket session.
     * Only transitions user to OFFLINE if ALL sessions have closed.
     */
    public synchronized void removeSession(String sessionId) {
        if (sessionId == null) return;

        Long userId = sessionToUser.remove(sessionId);
        if (userId != null) {
            Set<String> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    userSessions.remove(userId);
                    LocalDateTime lastSeen = LocalDateTime.now();
                    log.info("User ID: {} OFFLINE (last session: {})", userId, sessionId);
                    String username = null;
                    try {
                        var user = userService.getUserById(userId);
                        username = user.getUsername();
                        userService.updateUserStatus(userId, false, lastSeen);
                    } catch (Exception e) {
                        log.error("Failed to update offline status for user {}", userId, e);
                    }
                    broadcastPresence(userId, username, false, lastSeen);
                } else {
                    log.debug("User ID: {} closed session {} (remaining: {})", userId, sessionId, sessions.size());
                }
            }
        }
    }

    public boolean isUserOnline(Long userId) {
        if (userId == null) return false;
        Set<String> sessions = userSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    public Set<Long> getOnlineUsers() {
        return Collections.unmodifiableSet(userSessions.keySet());
    }

    private void broadcastPresence(Long userId, String username, boolean isOnline, LocalDateTime lastSeen) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("userId", userId);
            event.put("username", username);
            event.put("isOnline", isOnline);
            event.put("lastSeen", lastSeen != null ? lastSeen.toString() : null);
            messagingTemplate.convertAndSend("/topic/presence", event);
        } catch (Exception e) {
            log.error("Failed to broadcast presence for user {}", userId, e);
        }
    }
}
