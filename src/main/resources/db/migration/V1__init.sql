-- RealTalk 재건축 초기 스키마
-- Version: 1
-- Description: 전체 테이블 초기 생성

-- 1. users
CREATE TABLE users
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    role          ENUM ('USER','ADMIN') NOT NULL DEFAULT 'USER',
    refresh_token TEXT,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 2. user_profiles
CREATE TABLE user_profiles
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL UNIQUE,
    nickname   VARCHAR(255),
    bio        TEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 3. auths
CREATE TABLE auths
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT      NOT NULL UNIQUE,
    provider       VARCHAR(50) NOT NULL,
    provider_id    VARCHAR(255) NOT NULL,
    provider_email VARCHAR(255),
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT fk_auths_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_auths_provider UNIQUE (provider, provider_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 4. categories
CREATE TABLE categories
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(255) NOT NULL UNIQUE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 5. debate_rooms
CREATE TABLE debate_rooms
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    uuid                VARCHAR(36)  NOT NULL UNIQUE,
    creator_id          BIGINT       NOT NULL,
    category_id         BIGINT,
    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    side_a              VARCHAR(255) NOT NULL,
    side_b              VARCHAR(255) NOT NULL,
    turn_duration_secs  INT          NOT NULL,
    total_duration_secs INT          NOT NULL,
    max_speaker         INT          NOT NULL DEFAULT 2,
    max_audience        INT          NOT NULL DEFAULT 100,
    debate_type         ENUM ('NORMAL','FAST') NOT NULL DEFAULT 'NORMAL',
    status              ENUM ('WAITING','STARTED','ENDED') NOT NULL DEFAULT 'WAITING',
    started_at          DATETIME(6),
    ended_at            DATETIME(6),
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT fk_debate_rooms_creator FOREIGN KEY (creator_id) REFERENCES users (id),
    CONSTRAINT fk_debate_rooms_category FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_debate_rooms_status ON debate_rooms (status, created_at DESC);
CREATE INDEX idx_debate_rooms_category ON debate_rooms (category_id, status);

-- 6. debate_participants
CREATE TABLE debate_participants
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    debate_room_id   BIGINT      NOT NULL,
    user_id          BIGINT,
    guest_id         VARCHAR(255),
    participant_role ENUM ('SPEAKER','AUDIENCE') NOT NULL,
    side             ENUM ('A','B'),
    joined_at        DATETIME(6) NOT NULL,
    left_at          DATETIME(6),
    CONSTRAINT fk_debate_participants_room FOREIGN KEY (debate_room_id) REFERENCES debate_rooms (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_debate_participants_room ON debate_participants (debate_room_id);

-- 7. debate_speeches (신설: 발언 트랜스크립트 + AI 요약 영구 저장)
CREATE TABLE debate_speeches
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    debate_room_id   BIGINT      NOT NULL,
    turn_index       INT         NOT NULL,
    speaker_user_id  BIGINT,
    side             ENUM ('A','B') NOT NULL,
    transcript       TEXT,
    ai_summary       TEXT,
    spoken_at        DATETIME(6) NOT NULL,
    CONSTRAINT fk_debate_speeches_room FOREIGN KEY (debate_room_id) REFERENCES debate_rooms (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_debate_speeches_room ON debate_speeches (debate_room_id, turn_index);

-- 8. debate_chats (신설: 채팅 메시지 영구 저장)
CREATE TABLE debate_chats
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    debate_room_id  BIGINT       NOT NULL,
    sender_user_id  BIGINT,
    sender_guest_id VARCHAR(255),
    sender_name     VARCHAR(255) NOT NULL,
    message         TEXT         NOT NULL,
    sent_at         DATETIME(6)  NOT NULL,
    CONSTRAINT fk_debate_chats_room FOREIGN KEY (debate_room_id) REFERENCES debate_rooms (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_debate_chats_room ON debate_chats (debate_room_id, sent_at DESC);

-- 9. debate_results
CREATE TABLE debate_results
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    debate_room_id BIGINT      NOT NULL UNIQUE,
    ai_analysis    TEXT,
    side_a_votes   INT         NOT NULL DEFAULT 0,
    side_b_votes   INT         NOT NULL DEFAULT 0,
    created_at     DATETIME(6) NOT NULL,
    CONSTRAINT fk_debate_results_room FOREIGN KEY (debate_room_id) REFERENCES debate_rooms (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 10. debate_votes (신설: 투표 중복 방지)
CREATE TABLE debate_votes
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    debate_room_id  BIGINT     NOT NULL,
    voter_user_id   BIGINT,
    voter_guest_id  VARCHAR(255),
    side            ENUM ('A','B') NOT NULL,
    voted_at        DATETIME(6) NOT NULL,
    CONSTRAINT fk_debate_votes_result FOREIGN KEY (debate_room_id) REFERENCES debate_results (debate_room_id),
    CONSTRAINT uq_vote_user UNIQUE (debate_room_id, voter_user_id),
    CONSTRAINT uq_vote_guest UNIQUE (debate_room_id, voter_guest_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 11. debate_topics
CREATE TABLE debate_topics
(
    id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL UNIQUE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
