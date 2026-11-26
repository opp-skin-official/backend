package skin.opp.backend.product;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.ai.ProfileContext;
import skin.opp.backend.product.MatchScorer.ProductIngredient;
import skin.opp.backend.product.MatchScorer.Profile;
import skin.opp.backend.product.MatchScorer.Recommended;
import skin.opp.backend.product.MatchScorer.Result;
import skin.opp.backend.product.ProductDtos.ProductSummary;
import skin.opp.backend.product.ProductDtos.Recommendation;
import skin.opp.backend.product.ProductDtos.Recommendations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 맞춤 제품 추천: 피부 유형(최신 설문) + 상담 리포트의 추천·주의 성분 + 등록한 주의 성분으로 궁합 점수를 매긴다.
 * 점수 계산은 MatchScorer. 제품 성분은 product_ingredients(INCI, sort_order 순)만 쓴다.
 */
@Service
public class RecommendationService {

    static final int MAX_LIMIT = 30;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProfileContext profile;
    private final MatchScorer scorer;

    public RecommendationService(JdbcClient jdbc, ObjectMapper json, ProfileContext profile, IngredientRules rules) {
        this.jdbc = jdbc;
        this.json = json;
        this.profile = profile;
        this.scorer = new MatchScorer(rules);
    }

    /**
     * consultationId가 있으면 그 상담 리포트, 없으면 가장 최근 리포트의 성분 프로필을 쓴다(없으면 피부 유형만).
     * 점수 높은 순, 점수가 없으면(피부 유형 없음) 등록순.
     */
    public Recommendations recommend(long userId, Long consultationId, int limit) {
        Profile p = profile(userId, consultationId);
        List<ProductSummary> products = jdbc.sql("SELECT " + ProductService.SUMMARY_COLUMNS
                        + " FROM products p WHERE p.active ORDER BY p.created_at DESC, p.id DESC")
                .param("u", userId).query((rs, i) -> ProductService.summary(rs)).list();
        Map<Long, Result> results = score(p, products.stream().map(ProductSummary::id).toList());

        List<Recommendation> items = new ArrayList<>();
        for (ProductSummary product : products) {
            Result r = results.get(product.id());
            items.add(new Recommendation(product.withMatchScore(r.score()), r.score(), r.reasons(), r.cautions()));
        }
        items.sort(Comparator.comparing((Recommendation r) -> r.score() == null ? -1 : r.score()).reversed());
        return new Recommendations(p.baumann() != null, !p.recommended().isEmpty(),
                items.subList(0, Math.min(Math.clamp(limit, 1, MAX_LIMIT), items.size())));
    }

    /** 점수를 낼 사용자 정보: 피부 유형, 리포트 추천·주의 성분, 등록한 주의 성분 */
    Profile profile(long userId, Long consultationId) {
        JsonNode ingredientProfile = ingredientProfile(userId, consultationId);
        List<Recommended> recommended = new ArrayList<>();
        List<String> cautionKeys = new ArrayList<>();
        for (JsonNode n : ingredientProfile.path("recommended_ingredients")) {
            if (n.path("inci").isString()) {
                recommended.add(new Recommended(n.path("inci").asString(), n.path("label_ko").asString(n.path("inci").asString()),
                        n.path("score").asDouble(0)));
            }
        }
        for (JsonNode n : ingredientProfile.path("caution_ingredients")) {
            if (n.path("key").isString()) cautionKeys.add(n.path("key").asString());
        }
        return new Profile(profile.baumann(userId), recommended, cautionKeys, profile.alerts(userId));
    }

    /** 주어진 제품들의 점수 (제품 id → 결과). 주요 성분이 없는 제품도 결과가 있다. */
    Map<Long, Result> score(Profile p, Collection<Long> productIds) {
        Map<Long, List<ProductIngredient>> ingredients = new HashMap<>();
        if (!productIds.isEmpty()) {
            jdbc.sql("""
                    SELECT product_id, name_ko, inci FROM product_ingredients
                    WHERE product_id IN (:ids) ORDER BY product_id, sort_order
                    """).param("ids", productIds).query(rs -> {
                ingredients.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>())
                        .add(new ProductIngredient(rs.getString("name_ko"), rs.getString("inci")));
            });
        }
        Map<Long, Result> out = new HashMap<>();
        for (Long id : productIds) out.put(id, scorer.score(ingredients.getOrDefault(id, List.of()), p));
        return out;
    }

    private JsonNode ingredientProfile(long userId, Long consultationId) {
        String sql = consultationId == null
                ? "SELECT analysis::text FROM care_reports WHERE user_id = :u ORDER BY created_at DESC, id DESC LIMIT 1"
                : "SELECT analysis::text FROM care_reports WHERE user_id = :u AND conversation_id = :c";
        var spec = jdbc.sql(sql).param("u", userId);
        if (consultationId != null) spec = spec.param("c", consultationId);
        var analysis = spec.query(String.class).optional();
        if (consultationId != null && analysis.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상담 리포트를 찾을 수 없어요.");
        }
        return analysis.map(json::readTree).map(a -> a.path("ingredient_profile")).orElse(json.createObjectNode());
    }
}
