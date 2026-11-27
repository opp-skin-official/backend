package skin.opp.backend.product;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import skin.opp.backend.product.IngredientRules.Kind;
import skin.opp.backend.product.IngredientRules.Rule;

/**
 * 제품 궁합 점수 (AI API v4.2 명세 4.1·4.2):
 * MatchScore = clip(50 + 30·S_baumann + 20·S_concern − P_allergen, 0, 99), 바우만 유형이 없으면 null.
 * <ul>
 *   <li>S_baumann (−0.5~1): 사용자 유형 글자의 도움·주의 성분(성분 규칙 시트)이 제품 성분에 얼마나 앞쪽에 들어 있는지</li>
 *   <li>S_concern (0~1): 최근 리포트(Model A)의 추천 성분 점수와 제품 성분의 가중 코사인 유사도. 리포트 주의 성분이 있으면 절반</li>
 *   <li>리포트 주의 성분이나 등록한 주의 성분이 든 제품은 {@link Result#avoid()}: 맞춤 추천 목록에서 뺀다(RecommendationService)</li>
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

    /** "고함량" 주의 성분은 이 순번 안(앞쪽 10개)에 있을 때만 본다. 뒤쪽은 소량(pH 조절 등)이다. */
    static final int HIGH_CONTENT_RANGE = 10;

    /** 에센셜 오일로 보는 식물(INCI에 이 이름과 "oil"이 함께 있으면) */
    private static final List<String> ESSENTIAL_OIL_PLANTS = List.of(
            "lavandula", "melaleuca", "eucalyptus", "rosmarinus", "mentha", "citrus", "cananga", "pelargonium",
            "rosa damascena", "cymbopogon", "salvia", "juniperus", "cupressus", "origanum", "thymus", "cinnamomum",
            "eugenia caryophyllus", "santalum", "boswellia", "pogostemon", "anthemis nobilis", "chamomilla recutita",
            "litsea", "abies", "pinus", "cedrus", "commiphora", "vetiveria", "ylang");

    /**
     * 리포트 주의 성분 묶음. Model A의 key나 한글 이름(label_ko)에 든 낱말로 알아보고, 제품 INCI와 맞춘다.
     * highOnly면 고함량(앞쪽 {@link #HIGH_CONTENT_RANGE}개)일 때만 해당한다.
     */
    private record CautionGroup(Set<String> keys, List<String> labelWords, Predicate<String> inci, boolean highOnly) {
        boolean covers(Caution c) {
            if (c.key() != null && keys.contains(c.key())) return true;
            String label = c.labelKo() == null ? "" : c.labelKo().replace(" ", "");
            return labelWords.stream().anyMatch(w -> label.contains(w.replace(" ", "")));
        }
    }

    private static final List<CautionGroup> CAUTION_GROUPS = List.of(
            new CautionGroup(Set.of("synthetic_fragrance"), List.of("향료"), "fragrance"::equals, false),
            new CautionGroup(Set.of("high_alcohol_denat"), List.of("알코올"),
                    Set.of("alcohol denat.", "alcohol", "ethanol", "sd alcohol 40")::contains, true),
            new CautionGroup(Set.of("sulfate_surfactant"), List.of("설페이트"),
                    Set.of("sodium lauryl sulfate", "sodium laureth sulfate", "ammonium lauryl sulfate",
                            "ammonium laureth sulfate", "sodium coco-sulfate", "sodium myreth sulfate")::contains, false),
            new CautionGroup(Set.of(), List.of("에센셜 오일", "에센셜오일", "정유"),
                    inci -> inci.contains("oil") && ESSENTIAL_OIL_PLANTS.stream().anyMatch(inci::contains), false),
            new CautionGroup(Set.of(), List.of("각질제거", "AHA", "BHA"),
                    Set.of("glycolic acid", "lactic acid", "mandelic acid", "salicylic acid", "malic acid",
                            "tartaric acid", "betaine salicylate")::contains, true));

    /** 제품의 성분 하나 (sort_order 순서) */
    public record ProductIngredient(String nameKo, String inci) {}

    /** 리포트 추천 성분 (Model A ingredient_profile.recommended_ingredients) */
    public record Recommended(String inci, String labelKo, double score) {}

    /** 리포트 주의 성분 (Model A ingredient_profile.caution_ingredients의 key, label_ko) */
    public record Caution(String key, String labelKo) {}

    /** 점수를 낼 사람의 정보. baumann이 null이면 점수 없음 */
    public record Profile(String baumann, List<Recommended> recommended, List<Caution> reportCautions, List<String> alerts) {}

    /**
     * score: 0~99 또는 null(바우만 없음). reasons = 좋은 점, cautions = 주의할 점.
     * allergen = 등록한 주의 성분 포함, reportCaution = 리포트 주의 성분 포함.
     */
    public record Result(Integer score, double baumannScore, double concernScore, boolean allergen, boolean reportCaution,
                         List<String> reasons, List<String> cautions) {
        /** 맞춤 추천에서 빼야 하는 제품 (리포트·사용자가 피하라고 한 성분이 들어 있음) */
        public boolean avoid() {
            return allergen || reportCaution;
        }
    }

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
        boolean reportCaution = false;
        Set<CautionGroup> hitGroups = new LinkedHashSet<>();
        for (Caution c : profile.reportCautions()) {
            for (CautionGroup group : CAUTION_GROUPS) {
                if (!group.covers(c) || hitGroups.contains(group)) continue;
                int limit = group.highOnly() ? Math.min(HIGH_CONTENT_RANGE, incis.size()) : incis.size();
                for (int i = 0; i < limit; i++) {
                    if (!group.inci().test(incis.get(i))) continue;
                    if (hitGroups.add(group)) sConcern *= 0.5;
                    reportCaution = true;
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
        return new Result(score, sBaumann, sConcern, allergen, reportCaution,
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
