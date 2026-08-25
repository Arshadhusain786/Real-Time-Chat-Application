package com.chat.app.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Fixes legacy schema issues that JPA ddl-auto=update cannot handle
 * (dropping columns, changing NOT NULL to NULL on existing columns).
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class DatabaseMigrationConfig {

    private final DataSource dataSource;

    @Bean
    public ApplicationRunner schemaMigrationRunner() {
        return args -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            // Fix 1: Make receiver_id nullable (legacy column from old schema)
            try {
                jdbc.execute("ALTER TABLE messages MODIFY COLUMN receiver_id BIGINT NULL");
                log.info("Schema fix: messages.receiver_id set to nullable");
            } catch (Exception e) {
                // Column might not exist or already fixed - that's fine
                log.debug("Schema fix skipped for messages.receiver_id: {}", e.getMessage());
            }

            // Fix 2: Make conversations.name nullable (DIRECT chats have no name)
            try {
                jdbc.execute("ALTER TABLE conversations MODIFY COLUMN name VARCHAR(100) NULL");
                log.info("Schema fix: conversations.name set to nullable");
            } catch (Exception e) {
                log.debug("Schema fix skipped for conversations.name: {}", e.getMessage());
            }

            // Fix 3: Add type column if missing
            try {
                jdbc.execute("ALTER TABLE conversations ADD COLUMN IF NOT EXISTS type VARCHAR(10) DEFAULT 'DIRECT'");
                log.debug("Schema fix: conversations.type ensured");
            } catch (Exception e) {
                log.debug("Schema fix skipped for conversations.type: {}", e.getMessage());
            }
        };
    }
}
