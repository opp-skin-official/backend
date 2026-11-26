package skin.opp.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.auth.KakaoOAuth2UserService;

/** 필수 약관 동의: 동의 전에는 /api/me·동의·탈퇴만 되고, 현재 버전에 동의하면 열린다. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TermsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired JdbcClient jdbc;

    private long userId;

    @BeforeEach
    void newUserWithoutConsent() {
        userId = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "새 사용자", null, null)).getId();
    }

    private RequestPostProcessor login() {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, userId), KakaoOAuth2UserService.USER_ID));
    }

    private ResultActions agree(String version) throws Exception {
        return mvc.perform(post("/api/me/terms").with(login()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"" + version + "\"}"));
    }

    private long consents(String version) {
        return jdbc.sql("SELECT count(*) FROM consents WHERE user_id = :u AND version = :v")
                .param("u", userId).param("v", version).query(Long.class).single();
    }

    @Test
    void apisAreClosedUntilCurrentTermsAreAgreed() throws Exception {
        mvc.perform(get("/api/me").with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsAgreed").value(false))
                .andExpect(jsonPath("$.termsVersion").value(TermsService.CURRENT_VERSION));
        mvc.perform(get("/api/products").with(login()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("약관에 동의해야 이용할 수 있어요."));
        mvc.perform(post("/api/conversations").with(login()).with(csrf())).andExpect(status().isForbidden());

        // 화면에 보여 준 버전이 지금과 다르면 거절
        agree("2000-01-01").andExpect(status().isConflict());
        assertThat(consents("2000-01-01")).isZero();

        agree(TermsService.CURRENT_VERSION).andExpect(status().isNoContent());
        agree(TermsService.CURRENT_VERSION).andExpect(status().isNoContent()); // 두 번 눌러도 한 번만 기록
        assertThat(jdbc.sql("SELECT type FROM consents WHERE user_id = :u AND version = :v ORDER BY type")
                .param("u", userId).param("v", TermsService.CURRENT_VERSION).query(String.class).list())
                .containsExactly("PRIVACY", "TERMS");

        mvc.perform(get("/api/me").with(login())).andExpect(jsonPath("$.termsAgreed").value(true));
        mvc.perform(get("/api/products").with(login())).andExpect(status().isOk());
    }

    @Test
    void olderVersionOrRevokedConsentDoesNotCount() throws Exception {
        for (String type : TermsService.REQUIRED) {
            jdbc.sql("INSERT INTO consents (user_id, type, version) VALUES (:u, :t, '2000-01-01')")
                    .param("u", userId).param("t", type).update();
        }
        mvc.perform(get("/api/products").with(login())).andExpect(status().isForbidden()); // 약관이 바뀌면 다시 동의

        agree(TermsService.CURRENT_VERSION).andExpect(status().isNoContent());
        jdbc.sql("UPDATE consents SET revoked_at = now() WHERE user_id = :u AND type = 'PRIVACY'").param("u", userId).update();
        mvc.perform(get("/api/products").with(login())).andExpect(status().isForbidden());
    }

    @Test
    void userCanWithdrawWithoutAgreeing() throws Exception {
        mvc.perform(delete("/api/me").with(login()).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE id = :u").param("u", userId).query(Long.class).single()).isZero();
    }
}
