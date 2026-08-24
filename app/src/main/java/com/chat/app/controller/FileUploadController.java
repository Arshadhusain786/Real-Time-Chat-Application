package com.chat.app.controller;

import com.chat.app.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/upload")
@Slf4j
@RequiredArgsConstructor
public class FileUploadController {

    private final StorageService storageService;

    @PostMapping
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            StorageService.StorageResult result = storageService.store(file);

            return ResponseEntity.ok(Map.of(
                    "url", result.url(),
                    "originalName", result.originalName(),
                    "size", result.size(),
                    "fileType", result.fileType(),
                    "contentType", result.contentType()
            ));
        } catch (IllegalArgumentException | SecurityException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("File upload failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload failed"));
        }
    }
}
