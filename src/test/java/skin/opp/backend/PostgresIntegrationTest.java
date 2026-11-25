package skin.opp.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.User;
import skin.opp.backend.user.UserRepository;
import skin.opp.backend.user.UserService;

/**
 * 실제 PostgreSQL(Testcontainers)로 전체 앱을 띄워 Flyway 마이그레이션, 엔티티 매핑(validate),
 * 세션 테이블, 사용자 저장을 확인한다. Docker가 없으면 건너뛴다(CI에서는 실행).
 */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    UserService userService;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsCreateSessionTables() {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name in ('spring_session', 'spring_session_attributes')",
                Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void firstLoginCreatesUserAndNextLoginUpdatesProfile() {
        User created = userService.upsertFromKakao(new KakaoProfile(555L, "처음 이름", null, null));
        User updated = userService.upsertFromKakao(new KakaoProfile(555L, "바뀐 이름", "https://img.example/a.jpg", null));

        assertThat(updated.getId()).isEqualTo(created.getId());
        User stored = users.findByKakaoId(555L).orElseThrow();
        assertThat(stored.getNickname()).isEqualTo("바뀐 이름");
        assertThat(stored.getProfileImageUrl()).isEqualTo("https://img.example/a.jpg");
        assertThat(users.count()).isEqualTo(1);
    }
}
