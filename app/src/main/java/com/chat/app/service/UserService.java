package com.chat.app.service;

import com.chat.app.model.User;
import com.chat.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Transactional
    public User registerUser(String username, String email, String password, String displayName) {
        if (username == null || username.isBlank() || username.length() < 3) {
            throw new IllegalArgumentException("Username must be at least 3 characters");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (email == null || !email.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
            throw new IllegalArgumentException("Invalid email format");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .username(username.trim())
                .email(email.trim().toLowerCase())
                .password(passwordEncoder.encode(password))
                .displayName(displayName != null && !displayName.isBlank() ? displayName.trim() : username)
                .isActive(true)
                .isOnline(false)
                .status("Available")
                .build();

        User saved = userRepository.save(user);
        log.info("User registered: {} (ID: {})", saved.getUsername(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public User getUserById(Long id) {
        return userRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    public User updateUserStatus(Long userId, Boolean isOnline, LocalDateTime lastSeen) {
        User user = getUserById(userId);
        user.setIsOnline(isOnline);
        if (lastSeen != null) {
            user.setLastSeen(lastSeen);
        }
        return userRepository.save(user);
    }

    @Transactional
    public User updateUserProfile(Long userId, String displayName, String status, String profilePicture) {
        User user = getUserById(userId);
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName.trim());
        }
        if (status != null) {
            user.setStatus(status.trim());
        }
        if (profilePicture != null) {
            user.setProfilePicture(profilePicture);
        }
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<User> getAllActiveUsers(Long excludeUserId) {
        return userRepository.findAllByIsActiveTrueAndIdNotOrderByDisplayName(excludeUserId);
    }

    @Transactional(readOnly = true)
    public List<User> searchUsers(String query, Long currentUserId) {
        return userRepository.searchUsers(query, currentUserId);
    }

    @Transactional
    public void updateLastLogin(Long userId) {
        User user = getUserById(userId);
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
    }
}
