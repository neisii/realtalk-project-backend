-- ============================================
-- RealTalk 개발 DB 스키마 (realtalk_dev)
-- MySQL 8.x / InnoDB / utf8mb4
-- ============================================

-- DB 생성 & 기본 설정
CREATE DATABASE IF NOT EXISTS `realtalk_dev`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `realtalk_dev`;

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------
-- 1) 카테고리 (페이징 없음: 단순 리스트)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `category` (
                                          `category_id`   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK',
                                          `category_name` VARCHAR(255)    NOT NULL COMMENT '프론트 라벨(이모지 가능)',
    `description`   TEXT            NULL COMMENT '카테고리 설명',
    `icon`          VARCHAR(50)     NULL COMMENT '아이콘 클래스/이모지',
    `sort_order`    INT             NOT NULL DEFAULT 0 COMMENT '정렬 우선순위(작을수록 먼저)',
    `is_active`     BOOLEAN         NOT NULL DEFAULT TRUE COMMENT '활성 여부',
    `created_at`    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`category_id`),
    UNIQUE KEY `u_category_name` (`category_name`),
    KEY `idx_sort_active` (`is_active`, `sort_order`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='토론 카테고리';

-- ------------------------------------------------------------
-- 2) 사용자 / 프로필 / 소셜 인증
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
                                      `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK',
                                      `username`      VARCHAR(255)    NOT NULL COMMENT '표시명',
    `role`          ENUM('ADMIN','USER') NOT NULL DEFAULT 'USER' COMMENT '권한',
    `is_active`     BOOLEAN         NOT NULL DEFAULT TRUE COMMENT '계정 활성',
    `last_login_at` TIMESTAMP       NULL COMMENT '마지막 로그인',
    `created_at`    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_username` (`username`),
    KEY `idx_created` (`created_at`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='사용자';

CREATE TABLE IF NOT EXISTS `user_profile` (
                                              `user_profile_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK',
                                              `user_id`         BIGINT UNSIGNED NOT NULL COMMENT 'user.id',
                                              `nickname`        VARCHAR(20)     NULL COMMENT '토론 닉네임',
    `avatar_url`      TEXT            NULL COMMENT '프로필 이미지 URL',
    `bio`             TEXT            NULL COMMENT '소개',
    `created_at`      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_profile_id`),
    UNIQUE KEY `u_user` (`user_id`),
    CONSTRAINT `fk_profile_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='사용자 프로필';

CREATE TABLE IF NOT EXISTS `auth` (
                                      `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK',
                                      `user_id`        BIGINT UNSIGNED NOT NULL COMMENT 'user.id',
                                      `provider`       VARCHAR(50)     NOT NULL COMMENT 'kakao|google',
    `provider_id`    VARCHAR(100)    NOT NULL COMMENT '소셜 제공자 식별자',
    `provider_email` VARCHAR(255)    NULL COMMENT '소셜 이메일(선택)',
    `created_at`     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `u_provider_pid` (`provider`, `provider_id`),
    UNIQUE KEY `u_user` (`user_id`),
    CONSTRAINT `fk_auth_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='소셜 인증 매핑';

-- ------------------------------------------------------------
-- 3) 토론방 (공유 URL 키: room_uuid)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `debate_room` (
                                             `room_id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK(내부용)',
                                             `room_uuid`         CHAR(36)        NOT NULL COMMENT '공유 URL 키(UUID)',
    `user_id`           BIGINT UNSIGNED NOT NULL COMMENT '생성자 user.id',
    `category_id`       BIGINT UNSIGNED NOT NULL COMMENT 'category.category_id',
    `title`             VARCHAR(200)    NOT NULL COMMENT '토론 제목',
    `debate_type`       ENUM('NORMAL','FAST_3M') NULL DEFAULT 'NORMAL' COMMENT '일반토론|3분토론',
    `a_description`     TEXT            NOT NULL COMMENT 'A입장 설명',
    `b_description`     TEXT            NOT NULL COMMENT 'B입장 설명',
    `duration_seconds`  INT             NOT NULL DEFAULT 300 COMMENT '총 토론 시간(초)',
    `max_speaker`       INT             NOT NULL DEFAULT 4 COMMENT '최대 발언자 수',
    `max_audience`      INT             NOT NULL DEFAULT 20 COMMENT '최대 청중 수',
    `current_speaker`   INT             NOT NULL DEFAULT 0 COMMENT '현재 발언자 수',
    `current_audience`  INT             NOT NULL DEFAULT 0 COMMENT '현재 청중 수',
    `side_a`            VARCHAR(255)    NOT NULL COMMENT 'A입장 라벨(예: 찬성)',
    `side_b`            VARCHAR(255)    NOT NULL COMMENT 'B입장 라벨(예: 반대)',
    `status`            ENUM('WAITING','ACTIVE','EXTENDED','FINISHED') NOT NULL DEFAULT 'WAITING' COMMENT '대기|진행|연장|종료',
    `started_at`        TIMESTAMP       NULL COMMENT '시작 시각',
    `closed_at`         TIMESTAMP       NULL COMMENT '종료 시각',
    `created_at`        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`room_id`),
    UNIQUE KEY `u_room_uuid` (`room_uuid`),
    KEY `idx_category_status` (`category_id`, `status`),
    KEY `idx_creator` (`user_id`),
    KEY `idx_started` (`started_at`),
    CONSTRAINT `fk_room_category` FOREIGN KEY (`category_id`) REFERENCES `category`(`category_id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_room_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE RESTRICT
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='토론방';

-- ------------------------------------------------------------
-- 4) 토론 참여자
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `debate_participant` (
                                                    `participant_id`  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK',
                                                    `room_id`         BIGINT UNSIGNED NOT NULL COMMENT 'debate_room.room_id',
                                                    `user_id`         BIGINT UNSIGNED NOT NULL COMMENT 'user.id',
                                                    `role`            ENUM('SPEAKER','AUDIENCE') NOT NULL COMMENT '발언자|청중',
    `position`        ENUM('A','B')  NULL COMMENT '입장(A/B), 청중 NULL 가능',
    `nickname`        VARCHAR(20)    NOT NULL COMMENT '토론 닉네임 스냅샷',
    `status`          ENUM('WAITING','SPEAKING','DONE','LEFT') NOT NULL DEFAULT 'WAITING' COMMENT '대기|발언중|완료|퇴장',
    `joined_at`       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '입장 시각',
    `left_at`         TIMESTAMP      NULL COMMENT '퇴장 시각',
    PRIMARY KEY (`participant_id`),
    UNIQUE KEY `u_room_user` (`room_id`, `user_id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_role_pos` (`role`, `position`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_part_room` FOREIGN KEY (`room_id`) REFERENCES `debate_room`(`room_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_part_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='토론 참여자';

-- ------------------------------------------------------------
-- 5) 토론 결과(간이 요약)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `debate_result` (
                                               `result_id`   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'PK',
                                               `room_id`     BIGINT UNSIGNED NOT NULL COMMENT 'debate_room.room_id',
                                               `ai_summary`  LONGTEXT        NULL COMMENT '토론 요약(문장)',
                                               `closed_at`   TIMESTAMP       NULL COMMENT '토론 종료 시각',
                                               `created_at`  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                               PRIMARY KEY (`result_id`),
    KEY `idx_room` (`room_id`),
    CONSTRAINT `fk_result_room` FOREIGN KEY (`room_id`) REFERENCES `debate_room`(`room_id`) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='토론 종료 요약';

-- ------------------------------------------------------------
-- (선택) 6) 사용자 세션(리프레시/세션 토큰 분리)
-- ------------------------------------------------------------
-- CREATE TABLE IF NOT EXISTS `user_sessions` (
--   `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
--   `user_id`       BIGINT UNSIGNED NOT NULL,
--   `session_token` VARCHAR(255)    NOT NULL,
--   `refresh_token` VARCHAR(255)    NULL,
--   `expires_at`    TIMESTAMP       NOT NULL,
--   `user_agent`    TEXT            NULL,
--   `ip_address`    VARCHAR(45)     NULL,
--   `created_at`    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
--   PRIMARY KEY (`id`),
--   UNIQUE KEY `u_session` (`session_token`),
--   KEY `idx_user` (`user_id`),
--   KEY `idx_expires` (`expires_at`),
--   CONSTRAINT `fk_us_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='사용자 세션';

SET FOREIGN_KEY_CHECKS = 1;
