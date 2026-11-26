package skin.opp.backend.progress;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.TestUsers;
import skin.opp.backend.user.TermsService;
import skin.opp.backend.user.UserService;

/** 경과 기록 저장·수정·조회를 실제 PostgreSQL로 확인한다. 오늘은 2026-11-25(KST) 고정. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ProgressIntegrationTest.FixedClock.class)
class ProgressIntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId kst = ZoneId.of("Asia/Seoul");
            return Clock.fixed(TODAY.atTime(21, 0).atZone(kst).toInstant(), kst);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired TermsService terms;
    @Autowired ObjectMapper json;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = newUser();
    }

    private long newUser() {
        return TestUsers.member(userService, terms, "사용자");
    }

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    private String body(String change, String condition, String memo) {
        Map<String, Object> m = new HashMap<>();
        m.put("perceivedChange", change);
        m.put("skinCondition", condition);
        m.put("memo", memo);
        return json.writeValueAsString(m);
    }

    private org.springframework.test.web.servlet.ResultActions save(long id, LocalDate date, String body) throws Exception {
        return mvc.perform(put("/api/progress-records/" + date).with(login(id)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/progress-records?from=2026-11-23&to=2026-11-29")).andExpect(status().isUnauthorized());
    }

    @Test
    void savesOnePerDayAndUpdatesSameDay() throws Exception {
        save(userId, TODAY, body("SAME", "NORMAL", "  세안 후 당김  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memo").value("세안 후 당김"));
        save(userId, TODAY, body("BETTER", "COMFORT", " ")).andExpect(status().isOk());
        save(userId, TODAY.minusDays(2), body("WORSE", "UNCOMFORTABLE", null)).andExpect(status().isOk());

        mvc.perform(get("/api/progress-records?from=2026-11-23&to=2026-11-29").with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value("2026-11-23"))
                .andExpect(jsonPath("$[0].perceivedChange").value("WORSE"))
                .andExpect(jsonPath("$[1].date").value("2026-11-25"))
                .andExpect(jsonPath("$[1].skinCondition").value("COMFORT"))
                .andExpect(jsonPath("$[1].memo").doesNotExist());
    }

    @Test
    void rejectsFutureOldAndInvalidRecords() throws Exception {
        save(userId, TODAY.plusDays(1), body("SAME", "NORMAL", null)).andExpect(status().isBadRequest());
        save(userId, TODAY.minusDays(31), body("SAME", "NORMAL", null)).andExpect(status().isBadRequest());
        save(userId, TODAY, body("GREAT", "NORMAL", null)).andExpect(status().isBadRequest());
        save(userId, TODAY, body("SAME", null, null)).andExpect(status().isBadRequest());
        save(userId, TODAY, body("SAME", "NORMAL", "가".repeat(501))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/progress-records/" + TODAY).with(login(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body("SAME", "NORMAL", null)))
                .andExpect(status().isForbidden()); // CSRF 토큰 없음
    }

    @Test
    void listIsPerUserAndRangeIsLimited() throws Exception {
        save(userId, TODAY, body("SAME", "NORMAL", null)).andExpect(status().isOk());
        mvc.perform(get("/api/progress-records?from=2026-11-23&to=2026-11-29").with(login(newUser())))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/progress-records?from=2026-11-29&to=2026-11-23").with(login(userId)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/progress-records?from=2026-01-01&to=2026-11-29").with(login(userId)))
                .andExpect(status().isBadRequest());
    }
}
