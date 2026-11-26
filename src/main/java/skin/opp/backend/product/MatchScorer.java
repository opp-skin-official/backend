package skin.opp.backend.product;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import skin.opp.backend.product.IngredientRules.Kind;
import skin.opp.backend.product.IngredientRules.Rule;

/**
 * 제품 궁합 점수 (AI API v4.2 명세 4.1·4.2):
 * MatchScore = clip(50 + 30·S_baumann + 20·S_concern − P_allergen, 0, 99), 바우만 유형이 없으면 null.
 * <ul>
 *   <li>S_baumann (−0.5~1): 사용자 유형 글자의 도움·주의 성분(성분 규칙 시트)이 제품 성분에 얼마나 앞쪽에 들어 있는지</li>
 *   <li>S_concern (0~1): 최근 리포트(Model A)의 추천 성분 점수와 제품 성분의 가중 코사인 유사도. 리포트 주의 성분이 있으면 절반</li>
 *   <li>P_allergen: 등록한 주의(알레르기) 성분과 정확히 일치하면 60</li>
 * </ul>
 * 성분 순서 가중치 w_i = 1 / log2(i + 2) (i = 0부터, 앞쪽일수록 고함량).
 */
public final class MatchScorer {

    /** 도움 성분 가중치 합이 이 값이면 S_baumann의 도움 쪽이 1 (첫째·둘째 성분 정도) */
    static final double HELP_FULL = 1.5;
    /** 주의 성분 가중치 합이 이 값이면 주의 쪽이 1 */
    static final double CAUTION_FULL = 1.0;
    static final int ALLERGEN_PENALTY = 60;
    static final int MAX_REASONS = 3;

    /** 같은 성분의 다른 표기 (명세: 판테놀/D-판테놀/Panthenol) */
    private static final Map<String, String> ALIASES = Map.of(
            "d-panthenol", "panthenol",
            "dexpanthenol", "panthenol",
            "parfum", "fragrance",
            "alcohol denat", "alcohol denat.",
            "centella asiatica leaf extract", "centella asiatica extract");

    /** Model A caution_ingredients 키 중 INCI로 바로 맞출 수 있는 것 (나머지는 그룹이라 아직 못 맞춤) */
    static final Map<String, List<String>> CAUTION_KEY_INCI = Map.of(
            "synthetic_fragrance", List.of("fragrance"),
            "high_alcohol_denat", List.of("alcohol denat."),
            "sulfate_surfactant", List.of("sodium lauryl sulfate", "sodium laureth sulfate", "ammonium lauryl sulfate"));

    /** 제품의 성분 하나 (sort_order 순서) */
    public record ProductIngredient(String nameKo, String inci) {}

    /** 리포트 추천 성분 (Model A ingredient_profile.recommended_ingredients) */
    public record Recommended(String inci, String labelKo, double score) {}

    /** 점수를 낼 사람의 정보. baumann이 null이면 점수 없음 */
    public record Profile(String baumann, List<Recommended> recommended, List<String> reportCautionKeys, List<String> alerts) {}

    /** score: 0~99 또는 null(바우만 없음). reasons = 좋은 점, cautions = 주의할 점 */
    public record Result(Integer score, double baumannScore, double concernScore, boolean allergen,
                         List<String> reasons, List<String> cautions) {}

    private final IngredientRules rules;

    public MatchScorer(IngredientRules rules) {
        this.rules = rules;
    }

    public Result score(List<ProductIngredient> ingredients, Profile profile) {
        List<String> incis = ingredients.stream().map(i -> normalize(i.inci())).toList();
        List<String> reasons = new ArrayList<>();
        List<String> cautions = new ArrayList<>();

        // S_baumann
        double help = 0, caution = 0;
        Set<String> letters = letters(profile.baumann());
        Set<String> seenHelp = new LinkedHashSet<>(), seenCaution = new LinkedHashSet<>();
        for (Rule rule : rules.all()) {
            if (!letters.contains(String.valueOf(rule.letter()))) continue;
            int i = incis.indexOf(rule.inci());
            if (i < 0) continue;
            if (rule.kind() == Kind.HELP && seenHelp.add(rule.inci())) {
                help += weight(i);
                reasons.add(rule.ingredientKo() + ": " + rule.reason());
            } else if (rule.kind() == Kind.CAUTION && seenCaution.add(rule.inci())) {
                caution += weight(i);
                cautions.add("주의가 필요한 성분 " + rule.ingredientKo() + ": " + rule.reason());
            }
        }
        double sBaumann = clamp(Math.min(1, help / HELP_FULL) - Math.min(1, caution / CAUTION_FULL), -0.5, 1.0);

        // S_concern: 가중 코사인 (제품 벡터 w_i, 리포트 벡터 score)
        double dot = 0, productNorm = 0, reportNorm = 0;
        for (int i = 0; i < incis.size(); i++) productNorm += weight(i) * weight(i);
        for (Recommended r : profile.recommended()) {
            String inci = normalize(r.inci());
            reportNorm += r.score() * r.score();
            int i = incis.indexOf(inci);
            if (i >= 0) {
                dot += weight(i) * r.score();
                reasons.add("리포트 추천 성분 " + r.labelKo());
            }
        }
        double sConcern = productNorm == 0 || reportNorm == 0 ? 0 : dot / (Math.sqrt(productNorm) * Math.sqrt(reportNorm));
        for (String key : profile.reportCautionKeys()) {
            for (String inci : CAUTION_KEY_INCI.getOrDefault(key, List.of())) {
                int i = incis.indexOf(inci);
                if (i >= 0) {
                    sConcern *= 0.5;
                    cautions.add("리포트에서 주의가 필요하다고 한 성분 " + ingredients.get(i).nameKo());
                }
            }
        }
        sConcern = clamp(sConcern, 0, 1);

        // P_allergen: 등록한 주의 성분(한글 이름 또는 INCI) 정확 일치
        boolean allergen = false;
        for (String alert : profile.alerts()) {
            String a = normalize(alert);
            for (ProductIngredient ing : ingredients) {
                if (a.equals(normalize(ing.nameKo())) || a.equals(normalize(ing.inci()))) {
                    allergen = true;
                    cautions.addFirst("등록한 주의 성분 " + alert.strip() + " 포함");
                }
            }
        }

        Integer score = profile.baumann() == null ? null
                : (int) Math.round(clamp(50 + 30 * sBaumann + 20 * sConcern - (allergen ? ALLERGEN_PENALTY : 0), 0, 99));
        return new Result(score, sBaumann, sConcern, allergen,
                reasons.stream().distinct().limit(MAX_REASONS).toList(), cautions.stream().distinct().toList());
    }

    /** 앞쪽 성분일수록 큰 가중치: 1/log2(i+2) */
    static double weight(int i) {
        return 1 / (Math.log(i + 2) / Math.log(2));
    }

    /** INCI 비교용: 소문자, 공백 정리, 알려진 다른 표기 통일 */
    static String normalize(String inci) {
        if (inci == null) return "";
        String n = inci.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return ALIASES.getOrDefault(n, n);
    }

    /** "OSNT" → {O, S, N, T} */
    private static Set<String> letters(String baumann) {
        Set<String> out = new LinkedHashSet<>();
        if (baumann != null) for (char c : baumann.toCharArray()) out.add(String.valueOf(c));
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
