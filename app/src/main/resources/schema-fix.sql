-- Run this SQL against your chat_app database to fix schema issues.
-- These are fixes for columns that JPA ddl-auto=update cannot remove/modify.

-- Fix 1: Remove legacy receiver_id column from messages table (no longer used - conversation-based model)
ALTER TABLE messages MODIFY COLUMN receiver_id BIGINT NULL;

-- Fix 2: Allow null name on conversations table (DIRECT conversations have no name)
ALTER TABLE conversations MODIFY COLUMN name VARCHAR(100) NULL;
