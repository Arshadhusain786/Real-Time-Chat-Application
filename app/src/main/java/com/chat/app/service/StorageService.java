package com.chat.app.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class StorageService {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.upload.max-size:10485760}")
    private long maxFileSize; // 10MB default

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime"
    );

    private static final Set<String> ALLOWED_AUDIO_TYPES = Set.of(
            "audio/mpeg", "audio/wav", "audio/ogg", "audio/webm", "audio/mp4"
    );

    private static final Set<String> ALLOWED_DOC_TYPES = Set.of(
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain", "application/zip"
    );

    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(uploadDir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            log.info("Upload directory initialized: {}", path.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize upload directory", e);
        }
    }

    /**
     * Store a file and return its public URL path.
     */
    public StorageResult store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("File size exceeds maximum allowed (" + (maxFileSize / 1024 / 1024) + "MB)");
        }

        String contentType = file.getContentType();
        if (contentType == null || !isAllowedType(contentType)) {
            throw new IllegalArgumentException("File type not allowed: " + contentType);
        }

        // Sanitize filename and generate unique name
        String originalFilename = StringUtils.cleanPath(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "file");

        // Prevent path traversal
        if (originalFilename.contains("..")) {
            throw new SecurityException("Filename contains invalid path sequence");
        }

        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex >= 0) {
            extension = originalFilename.substring(dotIndex).toLowerCase();
        }

        String uniqueName = UUID.randomUUID().toString() + extension;
        Path targetPath = Paths.get(uploadDir).resolve(uniqueName);

        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        String url = "/uploads/" + uniqueName;
        String fileType = determineFileType(contentType);

        log.info("File stored: {} ({}, {} bytes) -> {}", originalFilename, contentType, file.getSize(), url);

        return new StorageResult(url, originalFilename, file.getSize(), fileType, contentType);
    }

    private boolean isAllowedType(String contentType) {
        return ALLOWED_IMAGE_TYPES.contains(contentType)
                || ALLOWED_VIDEO_TYPES.contains(contentType)
                || ALLOWED_AUDIO_TYPES.contains(contentType)
                || ALLOWED_DOC_TYPES.contains(contentType);
    }

    private String determineFileType(String contentType) {
        if (ALLOWED_IMAGE_TYPES.contains(contentType)) return "IMAGE";
        if (ALLOWED_VIDEO_TYPES.contains(contentType)) return "VIDEO";
        if (ALLOWED_AUDIO_TYPES.contains(contentType)) return "AUDIO";
        return "FILE";
    }

    public record StorageResult(String url, String originalName, long size, String fileType, String contentType) {}
}
