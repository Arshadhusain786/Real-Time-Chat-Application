package com.chat.app.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Fixes legacy schema issues that JPA ddl-auto=update cannot handle.
 * Uses PostgreSQL-compatible syntax.
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

            // Make receiver_id nullable if it exists (legacy column)
            safeExecute(jdbc, "ALTER TABLE messages ALTER COLUMN receiver_id DROP NOT NULL",
                    "messages.receiver_id set to nullable");

            // Make conversations.name nullable (DIRECT chats have no name)
            safeExecute(jdbc, "ALTER TABLE conversations ALTER COLUMN name DROP NOT NULL",
                    "conversations.name set to nullable");

            // Fix legacy conversations with NULL type — set them to DIRECT
            safeExecute(jdbc, "UPDATE conversations SET type = 'DIRECT' WHERE type IS NULL",
                    "set type=DIRECT for legacy conversations");
        };
    }

    private void safeExecute(JdbcTemplate jdbc, String sql, String description) {
        try {
            jdbc.execute(sql);
            log.info("Schema fix: {}", description);
        } catch (Exception e) {
            log.debug("Schema fix skipped ({}): {}", description, e.getMessage());
        }
    }
}
