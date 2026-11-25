package skin.opp.backend.product;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.UserService;

/** 제품 목록·필터·상세·내 보유제품을 실제 PostgreSQL로 확인한다. 제품은 테스트 안에서만 만든다. Docker 없으면 건너뜀. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProductIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired JdbcClient jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM user_products").update();
        jdbc.sql("DELETE FROM product_ingredients").update();
        jdbc.sql("DELETE FROM products").update();
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

    private long product(String brand, String name, String category, int price, String[] skins, boolean active) {
        return jdbc.sql("""
                INSERT INTO products (brand, name, category, volume, price, summary, recommendation, skin_fits, features,
                                      full_ingredients, active)
                VALUES (:b, :n, :c, '150ml', :p, '한 줄 설명', '이런 분께 추천해요', :s, ARRAY['저자극'], '정제수, 글리세린', :a)
                RETURNING id
                """).param("b", brand).param("n", name).param("c", category).param("p", price)
                .param("s", skins).param("a", active).query(Long.class).single();
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void filtersByCategorySearchSkinAndPrice() throws Exception {
        product("이니스프리", "그린티 폼 클렌저", "CLEANSER", 9_900, new String[] {"OILY", "SENSITIVE"}, true);
        product("센텔리아", "마데카 크림", "CREAM", 16_000, new String[] {"DRY", "SENSITIVE"}, true);
        product("노스카", "나인 흔적 앰플", "ESSENCE", 10_000, new String[] {"OILY"}, true);
        product("VT", "100% 리들샷", "ESSENCE", 36_900, new String[] {"COMBINATION"}, true);
        product("숨김", "판매 중지 크림", "CREAM", 5_000, new String[] {"DRY"}, false);

        mvc.perform(get("/api/products").with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/products?category=CREAM").with(login(userId)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("마데카 크림"));
        mvc.perform(get("/api/products?category=CLEANSER,ESSENCE&sort=PRICE_ASC").with(login(userId)))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].price").value(9900))
                .andExpect(jsonPath("$.items[2].price").value(36900));
        mvc.perform(get("/api/products?q=센텔").with(login(userId)))
                .andExpect(jsonPath("$.items[0].brand").value("센텔리아"));
        // %는 와일드카드가 아니라 글자로 검색한다.
        mvc.perform(get("/api/products?q=100%").with(login(userId)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].brand").value("VT"));
        mvc.perform(get("/api/products?skin=SENSITIVE&sort=PRICE_DESC").with(login(userId)))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("마데카 크림"));
        // 1만원 이하는 10,000원 미만, 1-2만원은 10,000원 이상 20,000원 미만
        mvc.perform(get("/api/products?price=UNDER_10K").with(login(userId)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].price").value(9900));
        mvc.perform(get("/api/products?price=FROM_10K_TO_20K,OVER_30K&sort=PRICE_ASC").with(login(userId)))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].price").value(10000));
        mvc.perform(get("/api/products?category=SOAP").with(login(userId))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products?q=" + "가".repeat(51)).with(login(userId))).andExpect(status().isBadRequest());
    }

    @Test
    void paginatesTwentyPerPage() throws Exception {
        for (int i = 0; i < 25; i++) {
            product("브랜드", "제품 " + i, "TONER", 1_000 + i, new String[] {}, true);
        }
        mvc.perform(get("/api/products?sort=PRICE_ASC").with(login(userId)))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/products?sort=PRICE_ASC&page=1").with(login(userId)))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].price").value(1020))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void detailIncludesIngredientsAndHidesInactive() throws Exception {
        long id = product("이니스프리", "그린티 폼 클렌저", "CLEANSER", 18_000, new String[] {"OILY"}, true);
        jdbc.sql("INSERT INTO product_ingredients (product_id, sort_order, name_ko, inci) VALUES (:p, 1, '녹차 추출물', 'Camellia Sinensis Leaf Extract'), (:p, 2, '글리세린', 'Glycerin')")
                .param("p", id).update();
        long hidden = product("숨김", "판매 중지", "CREAM", 1_000, new String[] {}, false);

        mvc.perform(get("/api/products/" + id).with(login(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation").value("이런 분께 추천해요"))
                .andExpect(jsonPath("$.skinFits[0]").value("OILY"))
                .andExpect(jsonPath("$.features[0]").value("저자극"))
                .andExpect(jsonPath("$.keyIngredients.length()").value(2))
                .andExpect(jsonPath("$.keyIngredients[0].inci").value("Camellia Sinensis Leaf Extract"))
                .andExpect(jsonPath("$.fullIngredients").value("정제수, 글리세린"));
        mvc.perform(get("/api/products/" + hidden).with(login(userId))).andExpect(status().isNotFound());
        mvc.perform(get("/api/products/999999").with(login(userId))).andExpect(status().isNotFound());
    }

    @Test
    void myProductsAddListRemovePerUser() throws Exception {
        long cream = product("센텔리아", "마데카 크림", "CREAM", 16_000, new String[] {}, true);
        long cleanser = product("이니스프리", "그린티 폼 클렌저", "CLEANSER", 18_000, new String[] {}, true);

        for (int i = 0; i < 2; i++) { // 같은 요청을 두 번 보내도 하나만 남는다
            mvc.perform(post("/api/my-products").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"productId\":" + cream + ",\"kind\":\"OWNED\"}")).andExpect(status().isNoContent());
        }
        mvc.perform(post("/api/my-products").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + cleanser + ",\"kind\":\"INTEREST\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/my-products").with(login(userId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":999999,\"kind\":\"OWNED\"}")).andExpect(status().isNotFound());

        mvc.perform(get("/api/my-products").with(login(userId)))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/my-products?kind=OWNED").with(login(userId)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].product.name").value("마데카 크림"))
                .andExpect(jsonPath("$[0].product.owned").value(true));
        mvc.perform(get("/api/products?category=CLEANSER").with(login(userId)))
                .andExpect(jsonPath("$.items[0].interested").value(true))
                .andExpect(jsonPath("$.items[0].owned").value(false));
        mvc.perform(get("/api/my-products").with(login(newUser())))
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(delete("/api/my-products/" + cleanser + "?kind=INTEREST").with(login(userId)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/my-products?kind=INTEREST").with(login(userId)))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
