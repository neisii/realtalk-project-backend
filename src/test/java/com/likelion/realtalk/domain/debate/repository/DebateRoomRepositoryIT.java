package com.likelion.realtalk.domain.debate.repository;

import com.likelion.realtalk.domain.category.entity.Category;
import com.likelion.realtalk.domain.category.repository.CategoryRepository;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.domain.debate.type.DebateType;
import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.repository.UserRepository;
import com.likelion.realtalk.domain.user.type.UserRole;
import com.likelion.realtalk.support.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class DebateRoomRepositoryIT extends BaseIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired DebateRoomRepository debateRoomRepository;

    private User creator;
    private Category category;

    @BeforeEach
    void setUp() {
        creator = userRepository.save(User.builder()
                .username("user-" + UUID.randomUUID())
                .role(UserRole.USER)
                .build());

        category = categoryRepository.save(Category.builder()
                .categoryName("category-" + UUID.randomUUID())
                .build());
    }

    @Test
    @DisplayName("V1__init.sql Flyway 마이그레이션 및 Hibernate validate 통과 — 스키마와 엔티티가 일치한다")
    void schemaAndEntities_match_afterFlywayMigration() {
        // 컨텍스트 기동에 성공했다는 것 자체가 Flyway + Hibernate validate 통과를 의미한다.
        // 추가로 실제 저장/조회가 되는지도 확인한다.
        DebateRoom room = debateRoomRepository.save(buildRoom(DebateStatus.WAITING, null));
        assertThat(room.getId()).isNotNull();
        assertThat(room.getCreatedAt()).isNotNull();
        assertThat(room.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("UUID로 방 조회 — 존재하는 UUID면 방을 반환한다")
    void findByUuid_existingUuid_returnsRoom() {
        String uuid = UUID.randomUUID().toString();
        debateRoomRepository.save(buildRoomWithUuid(uuid, DebateStatus.WAITING, null));

        assertThat(debateRoomRepository.findByUuid(uuid))
                .isPresent()
                .hasValueSatisfying(r -> {
                    assertThat(r.getUuid()).isEqualTo(uuid);
                    assertThat(r.getTitle()).isEqualTo("테스트 토론");
                });
    }

    @Test
    @DisplayName("UUID로 방 조회 — 존재하지 않는 UUID면 빈 Optional을 반환한다")
    void findByUuid_nonExistentUuid_returnsEmpty() {
        assertThat(debateRoomRepository.findByUuid("no-such-uuid")).isEmpty();
    }

    @Test
    @DisplayName("상태로 방 목록 조회 — WAITING 방만 페이지네이션 결과에 포함된다")
    void findByStatus_waiting_returnsOnlyWaitingRooms() {
        debateRoomRepository.save(buildRoom(DebateStatus.WAITING, null));
        debateRoomRepository.save(buildRoom(DebateStatus.WAITING, null));
        debateRoomRepository.save(buildRoom(DebateStatus.ENDED, null));

        Page<DebateRoom> result = debateRoomRepository.findByStatus(
                DebateStatus.WAITING, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(DebateStatus.WAITING));
    }

    @Test
    @DisplayName("카테고리 + 상태로 방 필터링 — 지정 카테고리의 WAITING 방만 반환한다")
    void findByCategoryIdAndStatus_filtersCorrectly() {
        Category otherCategory = categoryRepository.save(
                Category.builder().categoryName("other-" + UUID.randomUUID()).build());

        debateRoomRepository.save(buildRoom(DebateStatus.WAITING, category));
        debateRoomRepository.save(buildRoom(DebateStatus.WAITING, category));
        debateRoomRepository.save(buildRoom(DebateStatus.WAITING, otherCategory));

        Page<DebateRoom> result = debateRoomRepository.findByCategoryIdAndStatus(
                category.getId(), DebateStatus.WAITING, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent())
                .allSatisfy(r -> assertThat(r.getCategory().getId()).isEqualTo(category.getId()));
    }

    @Test
    @DisplayName("토론 시작 도메인 메서드 — WAITING → STARTED 상태로 전환되고 startedAt이 설정된다")
    void start_changesStatusToStarted() {
        DebateRoom room = debateRoomRepository.save(buildRoom(DebateStatus.WAITING, null));

        room.start();

        assertThat(room.getStatus()).isEqualTo(DebateStatus.STARTED);
        assertThat(room.getStartedAt()).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DebateRoom buildRoom(DebateStatus status, Category cat) {
        return buildRoomWithUuid(UUID.randomUUID().toString(), status, cat);
    }

    private DebateRoom buildRoomWithUuid(String uuid, DebateStatus status, Category cat) {
        return DebateRoom.builder()
                .uuid(uuid)
                .creator(creator)
                .category(cat)
                .title("테스트 토론")
                .sideA("찬성")
                .sideB("반대")
                .turnDurationSecs(90)
                .totalDurationSecs(600)
                .maxSpeaker(2)
                .maxAudience(100)
                .debateType(DebateType.NORMAL)
                .status(status)
                .build();
    }
}
