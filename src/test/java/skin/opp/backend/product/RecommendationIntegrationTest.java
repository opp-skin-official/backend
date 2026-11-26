package skin.opp.backend.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.UserService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 맞춤 추천: 피부 유형·리포트 성분·주의 성분이 순서와 점수에 반영되는지. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RecommendationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private long userId;
    private long soothing, plain, harsh, inactive;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM user_products").update();
        jdbc.sql("DELETE FROM product_ingredients").update();
        jdbc.sql("DELETE FROM products").update();
        userId = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "추천 사용자", null, null)).getId();
        soothing = product("진정 크림", true, "판테놀:Panthenol", "병풀추출물:Centella Asiatica Extract", "정제수:Water");
        plain = product("기본 로션", true, "정제수:Water", "글리세린:Glycerin");
        harsh = product("향 토너", true, "향료:Parfum", "변성알코올:Alcohol Denat.", "정제수:Water");
        inactive = product("판매 중지", false, "판테놀:Panthenol");
    }

    private RequestPostProcessor login(long id) {
        return oauth2Login().oauth2User(new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of(KakaoOAuth2UserService.USER_ID, id), KakaoOAuth2UserService.USER_ID));
    }

    /** "한글:INCI" 순서대로 주요 성분을 넣는다. */
    private long product(String name, boolean active, String... ingredients) {
        long id = jdbc.sql("""
                INSERT INTO products (brand, name, category, price, active) VALUES ('테스트', :n, 'CREAM', 10000, :a) RETURNING id
                """).param("n", name).param("a", active).query(Long.class).single();
        for (int i = 0; i < ingredients.length; i++) {
            String[] kv = ingredients[i].split(":", 2);
            jdbc.sql("INSERT INTO product_ingredients (product_id, sort_order, name_ko, inci) VALUES (:p, :o, :k, :i)")
                    .param("p", id).param("o", i + 1).param("k", kv[0]).param("i", kv[1]).update();
        }
        return id;
    }

    private void skinType(String surveyCode) {
        jdbc.sql("""
                INSERT INTO skin_type_assessments (user_id, question_version, answers, axis_results, survey_code,
                    result_type_code, environment_tags)
                VALUES (:u, 1, '{}', '{}', :code, 'TYPE_08', '{}')
                """).param("u", userId).param("code", surveyCode).update();
    }

    /** 상담 리포트 하나 (analysis.ingredient_profile만 채움) */
    private long report(String recommendedInci, String cautionKey) {
        long c = jdbc.sql("""
                INSERT INTO conversations (user_id, kind, title, state) VALUES (:u, 'CONSULT', '테스트 상담', 'COMPLETED') RETURNING id
                """).param("u", userId).query(Long.class).single();
        jdbc.sql("INSERT INTO consultation_summaries (conversation_id, answers, red_flag) VALUES (:c, '{}', false)")
                .param("c", c).update();
        String analysis = """
                {"ingredient_profile":{"recommended_ingredients":[{"key":"x","inci":"%s","label_ko":"추천","score":0.9}],
                 "caution_ingredients":[{"key":"%s","label_ko":"주의"}]}}""".formatted(recommendedInci, cautionKey);
        jdbc.sql("INSERT INTO care_reports (conversation_id, user_id, analysis) VALUES (:c, :u, CAST(:a AS jsonb))")
                .param("c", c).param("u", userId).param("a", analysis).update();
        return c;
    }

    private JsonNode recommendations(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/products/recommendations" + query).with(login(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/products/recommendations")).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutSkinTypeNoScoresButActiveProductsListed() throws Exception {
        JsonNode body = recommendations("");

        assertThat(body.path("hasSkinType").asBoolean()).isFalse();
        assertThat(body.path("usedReport").asBoolean()).isFalse();
        assertThat(body.path("items").size()).isEqualTo(3);
        assertThat(body.path("items").get(0).path("score").isNull()).isTrue();
        assertThat(body.toString()).doesNotContain("판매 중지");
    }

    @Test
    void skinTypeAndReportOrderProductsWithReasons() throws Exception {
        skinType("OSNT-L");
        report("Centella Asiatica Extract", "synthetic_fragrance");

        JsonNode items = recommendations("").path("items");

        assertThat(items.get(0).path("product").path("id").asLong()).isEqualTo(soothing);
        assertThat(items.get(1).path("product").path("id").asLong()).isEqualTo(plain);
        assertThat(items.get(2).path("product").path("id").asLong()).isEqualTo(harsh);
        // 50 + 30·1(판테놀·병풀 도움) + 20·cos([1, 0.63, 0.5]·[0, 0.9, 0]) = 50 + 30 + 9.8 → 90
        assertThat(items.get(0).path("score").asInt()).isEqualTo(90);
        assertThat(items.get(1).path("score").asInt()).isEqualTo(50);
        assertThat(items.get(2).path("score").asInt()).isLessThan(50);
        assertThat(items.get(0).path("reasons").toString()).contains("판테놀: 진정과 보습", "리포트 추천 성분 추천");
        assertThat(items.get(2).path("cautions").toString()).contains("주의가 필요한 성분 향료", "리포트에서 주의가 필요하다고 한 성분 향료");
    }

    @Test
    void registeredAlertPushesProductDown() throws Exception {
        skinType("OSNT-L");
        jdbc.sql("INSERT INTO skin_alerts (user_id, target) VALUES (:u, '판테놀')").param("u", userId).update();

        JsonNode items = recommendations("").path("items");

        JsonNode soothingItem = null;
        for (JsonNode i : items) if (i.path("product").path("id").asLong() == soothing) soothingItem = i;
        assertThat(soothingItem).isNotNull();
        assertThat(soothingItem.path("score").asInt()).isEqualTo(20); // 50 + 30 − 60
        assertThat(soothingItem.path("cautions").get(0).asString()).isEqualTo("등록한 주의 성분 판테놀 포함");
        assertThat(items.get(0).path("product").path("id").asLong()).isEqualTo(plain);
    }

    @Test
    void consultationIdUsesThatReportAndChecksOwner() throws Exception {
        skinType("OSNT-L");
        long first = report("Glycerin", "none");
        report("Centella Asiatica Extract", "none"); // 더 최근 리포트

        JsonNode withFirst = recommendations("?consultationId=" + first + "&limit=1");
        assertThat(withFirst.path("usedReport").asBoolean()).isTrue();
        assertThat(withFirst.path("items").size()).isEqualTo(1);
        assertThat(withFirst.path("items").get(0).path("reasons").toString()).doesNotContain("리포트 추천 성분 추천\"]");

        long other = userService.upsertFromKakao(new KakaoProfile(System.nanoTime(), "다른 사용자", null, null)).getId();
        mvc.perform(get("/api/products/recommendations?consultationId=" + first).with(login(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("상담 리포트를 찾을 수 없어요."));
        assertThat(inactive).isPositive();
    }
}
