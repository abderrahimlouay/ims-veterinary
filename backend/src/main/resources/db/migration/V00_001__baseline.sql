-- Phase 0 baseline (ADR-018, ADR-024, database-migration.md §9).
-- Fixes the default character set and collation of the application schema so that every
-- table created by later migrations inherits utf8mb4 / utf8mb4_0900_ai_ci. Idempotent.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
