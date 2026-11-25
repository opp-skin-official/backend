package skin.opp.backend.careinfo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.UserService;

/** 기타 정보(생활 환경, 주의 성분) API를 실제 PostgreSQL로 확인한다. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CareInfoIntegrationTest {

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

    private String body(Object value) {
        return json.writeValueAsString(value);
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/care-info")).andExpect(status().isUnauthorized());
    }

    @Test
    void emptyBeforeInput() throws Exception {
        mvc.perform(get("/api/care-info").with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").doesNotExist())
                .andExpect(jsonPath("$.alerts.length()").value(0));
    }

    @Test
    void savesAndUpdatesEnvironment() throws Exception {
        mvc.perform(put("/api/care-info/environment").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("washTime", "EVENING_ONLY", "outdoorTraining", "TWO_TO_THREE_PER_WEEK",
                                "allergyExperience", "YES"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.washTime").value("EVENING_ONLY"));

        mvc.perform(put("/api/care-info/environment").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("washTime", "VARIES", "outdoorTraining", "IRREGULAR",
                                "allergyExperience", "NO"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/care-info").with(login(userId)))
                .andExpect(jsonPath("$.environment.washTime").value("VARIES"))
                .andExpect(jsonPath("$.environment.outdoorTraining").value("IRREGULAR"))
                .andExpect(jsonPath("$.environment.allergyExperience").value("NO"));
    }

    @Test
    void rejectsUnknownOrMissingEnvironmentValues() throws Exception {
        mvc.perform(put("/api/care-info/environment").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("washTime", "NIGHT", "outdoorTraining", "IRREGULAR", "allergyExperience", "NO"))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/care-info/environment").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("washTime", "VARIES"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addsTrimsDeduplicatesAndDeletesAlerts() throws Exception {
        mvc.perform(post("/api/care-info/alerts").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("target", "  향료 (Fragrance) "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.target").value("향료 (Fragrance)"));
        mvc.perform(post("/api/care-info/alerts").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("target", "향료 (Fragrance)"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/care-info/alerts").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("target", "   "))))
                .andExpect(status().isBadRequest());

        String response = mvc.perform(get("/api/care-info").with(login(userId)))
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        long alertId = json.readTree(response).path("alerts").get(0).path("id").asLong();

        long other = newUser();
        mvc.perform(delete("/api/care-info/alerts/" + alertId).with(login(other)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/care-info/alerts/" + alertId).with(login(userId)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/care-info").with(login(userId)))
                .andExpect(jsonPath("$.alerts.length()").value(0));
    }

    @Test
    void limitsNumberOfAlerts() throws Exception {
        for (int i = 0; i < CareInfoService.MAX_ALERTS; i++) {
            mvc.perform(post("/api/care-info/alerts").with(login(userId)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("target", "성분" + i))))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/care-info/alerts").with(login(userId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("target", "하나 더"))))
                .andExpect(status().isBadRequest());
    }
}
