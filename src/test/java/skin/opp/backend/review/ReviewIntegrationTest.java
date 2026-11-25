package skin.opp.backend.review;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.UserService;

/** 제품 리뷰 작성·수정·삭제·목록을 실제 PostgreSQL로 확인한다. 제품은 테스트 안에서만 만든다. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ReviewIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private long cleanser;
    private long cream;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM product_reviews").update();
        jdbc.sql("DELETE FROM products").update();
        cleanser = product("CLEANSER", true);
        cream = product("CREAM", true);
    }

    private long product(String category, boolean active) {
        return jdbc.sql("INSERT INTO products (brand, name, category, price, active) VALUES ('브랜드', '제품', :c, 10000, :a) RETURNING id")
                .param("c", category).param("a", active).query(Long.class).single();
    }

    private long newUser(String nickname) {
        return userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), nickname, null, null)).getId();
    }

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    private String body(Integer rating, String content, String skin, List<String> good, List<String> bad) {
        Map<String, Object> m = new HashMap<>();
        m.put("rating", rating);
        m.put("content", content);
        m.put("skinOption", skin);
        m.put("goodTags", good);
        m.put("badTags", bad);
        return json.writeValueAsString(m);
    }

    private ResultActions save(long user, long product, String body) throws Exception {
        return mvc.perform(put("/api/products/" + product + "/reviews/me").with(login(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void formOffersTagsByCategory() throws Exception {
        long u = newUser("사용자");
        mvc.perform(get("/api/products/" + cleanser + "/review-form").with(login(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goodTagOptions[0]").value("세정력이 좋아요"))
                .andExpect(jsonPath("$.badTagOptions.length()").value(3))
                .andExpect(jsonPath("$.myReview").doesNotExist());
        mvc.perform(get("/api/products/" + cream + "/review-form").with(login(u)))
                .andExpect(jsonPath("$.goodTagOptions[0]").value("보습력이 좋아요"))
                .andExpect(jsonPath("$.badTagOptions.length()").value(4));
        mvc.perform(get("/api/products/" + product("CREAM", false) + "/review-form").with(login(u)))
                .andExpect(status().isNotFound());
    }

    @Test
    void savesOneReviewPerUserAndUpdates() throws Exception {
        long u = newUser("김네모");
        save(u, cleanser, body(4, "  수분감이 좋아요 산뜻해요.  ", "OILY", List.of("산뜻해요", "산뜻해요"), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("수분감이 좋아요 산뜻해요."))
                .andExpect(jsonPath("$.goodTags.length()").value(1))
                .andExpect(jsonPath("$.nickname").value("김네모"))
                .andExpect(jsonPath("$.mine").value(true));
        save(u, cleanser, body(2, "써 보니 건조해요", null, List.of(), List.of("건조해요")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(2))
                .andExpect(jsonPath("$.skinOption").doesNotExist());
        mvc.perform(get("/api/products/" + cleanser + "/reviews").with(login(u)))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.average").value(2.0))
                .andExpect(jsonPath("$.items[0].badTags[0]").value("건조해요"));
    }

    @Test
    void listShowsAverageAndMarksMine() throws Exception {
        long a = newUser("가");
        long b = newUser("나");
        long c = newUser("다");
        save(a, cream, body(5, "좋아요", null, List.of("보습력이 좋아요"), List.of())).andExpect(status().isOk());
        save(b, cream, body(4, "괜찮아요", "DRY", List.of(), List.of("끈적임이 있어요"))).andExpect(status().isOk());
        save(c, cream, body(4, "무난해요", null, List.of(), List.of())).andExpect(status().isOk());

        mvc.perform(get("/api/products/" + cream + "/reviews").with(login(b)))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.average").value(4.3))
                .andExpect(jsonPath("$.items[0].nickname").value("다"))
                .andExpect(jsonPath("$.items[0].mine").value(false))
                .andExpect(jsonPath("$.items[1].mine").value(true))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/products/" + cleanser + "/reviews").with(login(b)))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.average").value(0.0));
    }

    @Test
    void validatesReview() throws Exception {
        long u = newUser("사용자");
        save(u, cleanser, body(0, "내용", null, List.of(), List.of())).andExpect(status().isBadRequest());
        save(u, cleanser, body(6, "내용", null, List.of(), List.of())).andExpect(status().isBadRequest());
        save(u, cleanser, body(5, "   ", null, List.of(), List.of())).andExpect(status().isBadRequest());
        save(u, cleanser, body(5, "가".repeat(501), null, List.of(), List.of())).andExpect(status().isBadRequest());
        save(u, cleanser, body(5, "내용", "NORMAL", List.of(), List.of())).andExpect(status().isBadRequest());
        // 크림용 태그를 클렌저 리뷰에 쓸 수 없다.
        save(u, cleanser, body(5, "내용", null, List.of("보습력이 좋아요"), List.of())).andExpect(status().isBadRequest());
        save(u, 999_999, body(5, "내용", null, List.of(), List.of())).andExpect(status().isNotFound());
        mvc.perform(put("/api/products/" + cleanser + "/reviews/me").with(login(u))
                .contentType(MediaType.APPLICATION_JSON).content(body(5, "내용", null, List.of(), List.of())))
                .andExpect(status().isForbidden()); // CSRF 토큰 없음
    }

    @Test
    void deletesOnlyOwnReview() throws Exception {
        long writer = newUser("작성자");
        long other = newUser("다른 사람");
        save(writer, cream, body(5, "좋아요", null, List.of(), List.of())).andExpect(status().isOk());

        mvc.perform(delete("/api/products/" + cream + "/reviews/me").with(login(other)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/products/" + cream + "/reviews/me").with(login(writer)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/products/" + cream + "/reviews").with(login(writer)))
                .andExpect(jsonPath("$.count").value(0));
    }
}
