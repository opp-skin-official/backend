package skin.opp.backend.routine;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.UserService;

/** 관리 계획·오늘의 루틴·체크를 실제 PostgreSQL로 확인한다. 오늘은 2026-11-25(수, KST)로 고정. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(RoutineIntegrationTest.FixedClock.class)
class RoutineIntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId kst = ZoneId.of("Asia/Seoul");
            return Clock.fixed(TODAY.atTime(10, 0).atZone(kst).toInstant(), kst);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired ObjectMapper json;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = newUser();
    }

    private long newUser() {
        return userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "사용자", null, null)).getId();
    }

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    private String plan(String type, String label, LocalDate target, String focus) {
        Map<String, Object> body = new HashMap<>();
        body.put("goalType", type);
        body.put("goalLabel", label);
        body.put("targetDate", target.toString());
        body.put("focusGoal", focus);
        return json.writeValueAsString(body);
    }

    private JsonNode today(long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/routines/today").with(login(id)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void noPlanReturns404() throws Exception {
        mvc.perform(get("/api/routines/today").with(login(userId))).andExpect(status().isNotFound());
        mvc.perform(get("/api/care-plans/current").with(login(userId))).andExpect(status().isNotFound());
    }

    @Test
    void createsPlanWithDdayAndDefaultRoutine() throws Exception {
        mvc.perform(post("/api/care-plans").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(plan("VACATION", null, TODAY.plusDays(14), "BASIC_ROUTINE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysLeft").value(14))
                .andExpect(jsonPath("$.startDate").value("2026-11-25"))
                .andExpect(jsonPath("$.goalLabel").doesNotExist());

        mvc.perform(get("/api/routines/today").with(login(userId)))
                .andExpect(jsonPath("$.date").value("2026-11-25"))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].timeOfDay").value("MORNING"))
                .andExpect(jsonPath("$.items[0].title").value("순하게 세안하기"))
                .andExpect(jsonPath("$.items[3].timeOfDay").value("EVENING"))
                .andExpect(jsonPath("$.doneCount").value(0))
                // 계획이 수요일 시작 → 수~일 5일 × 2 = 10회
                .andExpect(jsonPath("$.week.start").value("2026-11-23"))
                .andExpect(jsonPath("$.week.scheduled").value(10))
                .andExpect(jsonPath("$.week.days[0].status").value("OUT_OF_PLAN"))
                .andExpect(jsonPath("$.week.days[3].status").value("FUTURE"));
    }

    @Test
    void checkingCompletesSlotsAndUncheckReverts() throws Exception {
        mvc.perform(post("/api/care-plans").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(plan("OUTING", null, TODAY.plusDays(7), "BASIC_ROUTINE"))).andExpect(status().isCreated());
        JsonNode items = today(userId).path("items");
        long morningWash = items.get(0).path("id").asLong();
        long morningMoist = items.get(1).path("id").asLong();

        mvc.perform(put("/api/routine-items/" + morningWash + "/checks/" + TODAY).with(login(userId)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/routine-items/" + morningWash + "/checks/" + TODAY).with(login(userId)).with(csrf()))
                .andExpect(status().isNoContent()); // 중복 체크는 그대로
        mvc.perform(get("/api/routines/today").with(login(userId)))
                .andExpect(jsonPath("$.doneCount").value(1))
                .andExpect(jsonPath("$.week.completed").value(0))
                .andExpect(jsonPath("$.week.days[2].status").value("PARTIAL"));

        mvc.perform(put("/api/routine-items/" + morningMoist + "/checks/" + TODAY).with(login(userId)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/routines/today").with(login(userId)))
                .andExpect(jsonPath("$.doneCount").value(2))
                .andExpect(jsonPath("$.week.completed").value(1))
                .andExpect(jsonPath("$.week.percent").value(10));

        mvc.perform(delete("/api/routine-items/" + morningMoist + "/checks/" + TODAY).with(login(userId)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/routines/today").with(login(userId)))
                .andExpect(jsonPath("$.doneCount").value(1))
                .andExpect(jsonPath("$.week.completed").value(0));
    }

    @Test
    void rejectsFutureOutOfPlanAndOthersItems() throws Exception {
        mvc.perform(post("/api/care-plans").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(plan("OUTING", null, TODAY.plusDays(7), "BASIC_ROUTINE"))).andExpect(status().isCreated());
        long item = today(userId).path("items").get(0).path("id").asLong();

        mvc.perform(put("/api/routine-items/" + item + "/checks/" + TODAY.plusDays(1)).with(login(userId)).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/routine-items/" + item + "/checks/" + TODAY.minusDays(1)).with(login(userId)).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/routine-items/" + item + "/checks/" + TODAY).with(login(newUser())).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/routine-items/" + item + "/checks/" + TODAY).with(login(userId)))
                .andExpect(status().isForbidden()); // CSRF 토큰 없음
    }

    @Test
    void validatesPlanAndReplacesPreviousPlan() throws Exception {
        mvc.perform(post("/api/care-plans").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(plan("VACATION", null, TODAY.minusDays(1), "BASIC_ROUTINE"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/care-plans").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(plan("VACATION", null, TODAY.plusDays(400), "BASIC_ROUTINE"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/care-plans").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(plan("CUSTOM", "  ", TODAY.plusDays(3), "SKIN_CHANGE"))).andExpect(status().isBadRequest());

        mvc.perform(post("/api/care-plans").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(plan("CUSTOM", " 전역 ", TODAY, "SKIN_CHANGE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goalLabel").value("전역"))
                .andExpect(jsonPath("$.daysLeft").value(0));
        long firstItem = today(userId).path("items").get(0).path("id").asLong();

        mvc.perform(post("/api/care-plans").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(plan("OVERNIGHT", "무시됨", TODAY.plusDays(5), "PRODUCT_REACTION")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goalType").value("OVERNIGHT"))
                .andExpect(jsonPath("$.goalLabel").doesNotExist());
        mvc.perform(get("/api/care-plans/current").with(login(userId)))
                .andExpect(jsonPath("$.goalType").value("OVERNIGHT"));
        // 이전 계획의 항목은 더 이상 체크할 수 없다.
        mvc.perform(put("/api/routine-items/" + firstItem + "/checks/" + TODAY).with(login(userId)).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
