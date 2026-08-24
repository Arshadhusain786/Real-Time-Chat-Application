package com.chat.app.controller;

import com.chat.app.model.User;
import com.chat.app.service.PresenceService;
import com.chat.app.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@Slf4j
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final PresenceService presenceService;

    @GetMapping
    public ResponseEntity<?> getAllUsers() {
        User currentUser = getAuthenticatedUser();
        List<User> users = userService.getAllActiveUsers(currentUser.getId());

        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("displayName", u.getDisplayName());
            map.put("profilePicture", u.getProfilePicture());
            map.put("status", u.getStatus());
            map.put("isOnline", presenceService.isUserOnline(u.getId()));
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchUsers(@RequestParam String query) {
        User currentUser = getAuthenticatedUser();

        if (query == null || query.isBlank() || query.length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query too short"));
        }

        List<User> users = userService.searchUsers(query, currentUser.getId());

        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("displayName", u.getDisplayName());
            map.put("profilePicture", u.getProfilePicture());
            map.put("isOnline", presenceService.isUserOnline(u.getId()));
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserProfile(@PathVariable Long userId) {
        try {
            User user = userService.getUserById(userId);
            Map<String, Object> response = new HashMap<>();
            response.put("id", user.getId());
            response.put("username", user.getUsername());
            response.put("displayName", user.getDisplayName());
            response.put("status", user.getStatus());
            response.put("isOnline", presenceService.isUserOnline(user.getId()));
            response.put("lastSeen", user.getLastSeen());
            response.put("profilePicture", user.getProfilePicture());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateMyProfile(@RequestBody Map<String, String> request) {
        User currentUser = getAuthenticatedUser();
        try {
            User user = userService.updateUserProfile(
                    currentUser.getId(),
                    request.get("displayName"),
                    request.get("status"),
                    request.get("profilePicture")
            );

            Map<String, Object> response = new HashMap<>();
            response.put("id", user.getId());
            response.put("displayName", user.getDisplayName());
            response.put("status", user.getStatus());
            response.put("profilePicture", user.getProfilePicture());
            response.put("message", "Profile updated");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private User getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
