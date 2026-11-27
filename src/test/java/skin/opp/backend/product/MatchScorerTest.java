package skin.opp.backend.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;
import skin.opp.backend.product.MatchScorer.ProductIngredient;
import skin.opp.backend.product.MatchScorer.Caution;
import skin.opp.backend.product.MatchScorer.Profile;
import skin.opp.backend.product.MatchScorer.Recommended;
import skin.opp.backend.product.MatchScorer.Result;

/** 실제 성분 규칙 시트로 점수 공식을 확인한다. */
class MatchScorerTest {

    private final IngredientRules rules = new IngredientRules();
    private final MatchScorer scorer = new MatchScorer(rules);

    private static ProductIngredient ing(String ko, String inci) {
        return new ProductIngredient(ko, inci);
    }

    private static Profile osnt() {
        return new Profile("OSNT", List.of(), List.of(), List.of());
    }

    @Test
    void rulesSheetLoadsOnlyIngredientRulesWithInci() {
        assertThat(rules.all()).isNotEmpty();
        assertThat(rules.all()).allSatisfy(r -> assertThat(r.inci()).isNotBlank());
        // 그룹(에센셜 오일류)·사용법(낮 시간 레티놀) 규칙은 빠진다.
        assertThat(rules.all()).noneMatch(r -> r.ingredientKo().equals("에센셜 오일류"));
        assertThat(rules.all()).noneMatch(r -> r.ingredientKo().equals("낮 시간 레티놀 사용"));
        assertThat(rules.all()).anyMatch(r -> r.letter() == 'S' && r.inci().equals("panthenol"));
    }

    @Test
    void positionWeightsFollowSpec() {
        assertThat(MatchScorer.weight(0)).isCloseTo(1.0, within(1e-9));
        assertThat(MatchScorer.weight(1)).isCloseTo(1 / (Math.log(3) / Math.log(2)), within(1e-9));
        assertThat(MatchScorer.weight(0)).isGreaterThan(MatchScorer.weight(5));
    }

    @Test
    void helpIngredientsForUserLettersRaiseScoreAndExplainWhy() {
        // OSNT: O 도움 나이아신아마이드, S 도움 판테놀·병풀추출물
        Result r = scorer.score(List.of(
                ing("판테놀", "Panthenol"), ing("병풀추출물", "Centella Asiatica Extract"), ing("정제수", "Water")), osnt());

        assertThat(r.baumannScore()).isEqualTo(1.0); // 1 + 0.63 ≥ 1.5 → 도움 쪽 꽉 참
        assertThat(r.score()).isEqualTo(80);         // 50 + 30·1 + 20·0
        assertThat(r.reasons()).containsExactly("판테놀: 진정과 보습", "병풀추출물: 붉어짐 진정에 도움");
        assertThat(r.cautions()).isEmpty();
    }

    @Test
    void earlierHelpIngredientCountsMoreThanLaterOne() {
        Result front = scorer.score(List.of(ing("판테놀", "Panthenol"), ing("정제수", "Water"), ing("글리세린", "Water2")), osnt());
        Result back = scorer.score(List.of(ing("정제수", "Water"), ing("부틸렌글라이콜", "Butylene Glycol"), ing("판테놀", "Panthenol")), osnt());
        assertThat(front.baumannScore()).isGreaterThan(back.baumannScore());
    }

    @Test
    void cautionIngredientsLowerScoreButNotBelowFloor() {
        // S·O 주의: 향료(Fragrance=Parfum 표기 통일), 변성알코올
        Result r = scorer.score(List.of(ing("향료", "Parfum"), ing("변성알코올", "Alcohol Denat.")), osnt());

        assertThat(r.baumannScore()).isEqualTo(-0.5);
        assertThat(r.score()).isEqualTo(35); // 50 − 15
        assertThat(r.cautions()).anyMatch(c -> c.startsWith("주의가 필요한 성분 향료"));
    }

    @Test
    void otherLettersRulesDoNotApply() {
        // D(건성) 도움 성분 세라마이드는 OSNT 사용자에게는 S 도움 성분으로만 셈(S에도 있음), 콜레스테롤(D만)은 안 셈
        Result r = scorer.score(List.of(ing("콜레스테롤", "Cholesterol")), osnt());
        assertThat(r.baumannScore()).isZero();
        assertThat(r.score()).isEqualTo(50);
    }

    @Test
    void reportRecommendationsUseWeightedCosine() {
        Profile p = new Profile("OSNT",
                List.of(new Recommended("Centella Asiatica Extract", "병풀추출물", 0.9),
                        new Recommended("Panthenol", "판테놀", 0.88)),
                List.of(), List.of());
        Result matched = scorer.score(List.of(ing("병풀추출물", "Centella Asiatica Leaf Extract"), ing("판테놀", "D-Panthenol")), p);
        Result unrelated = scorer.score(List.of(ing("정제수", "Water"), ing("글리세린", "Glycerin")), p);

        assertThat(matched.concernScore()).isGreaterThan(0.9).isLessThanOrEqualTo(1.0);
        assertThat(unrelated.concernScore()).isZero();
        assertThat(matched.reasons()).contains("리포트 추천 성분 병풀추출물");
        assertThat(matched.score()).isGreaterThan(unrelated.score());
    }

    @Test
    void reportCautionKeyHalvesConcernScore() {
        Profile clean = new Profile("OSNT", List.of(new Recommended("Panthenol", "판테놀", 0.9)), List.of(), List.of());
        Profile withCaution = new Profile("OSNT", List.of(new Recommended("Panthenol", "판테놀", 0.9)),
                List.of(new Caution("synthetic_fragrance", "인공 향료")), List.of());
        List<ProductIngredient> product = List.of(ing("판테놀", "Panthenol"), ing("향료", "Fragrance"));

        assertThat(scorer.score(product, withCaution).concernScore())
                .isCloseTo(scorer.score(product, clean).concernScore() / 2, within(1e-9));
        assertThat(scorer.score(product, withCaution).cautions()).anyMatch(c -> c.contains("리포트에서 주의가 필요하다고 한 성분 향료"));
        assertThat(scorer.score(product, withCaution).avoid()).isTrue();
        assertThat(scorer.score(product, clean).avoid()).isFalse();
    }

    private Result withReportCaution(String key, String label, ProductIngredient... ingredients) {
        return scorer.score(List.of(ingredients), new Profile("OSNT", List.of(), List.of(new Caution(key, label)), List.of()));
    }

    @Test
    void reportCautionGroupsAreRecognizedByKeyOrKoreanLabel() {
        // 키로
        assertThat(withReportCaution("high_alcohol_denat", null, ing("변성알코올", "Alcohol Denat.")).avoid()).isTrue();
        assertThat(withReportCaution("sulfate_surfactant", null, ing("소듐라우릴설페이트", "Sodium Lauryl Sulfate")).avoid()).isTrue();
        // 키를 몰라도 한글 이름으로 (화면 문구: 고농축 에센셜 오일, 고함량 각질제거 산)
        assertThat(withReportCaution("unknown_key", "고농축 에센셜 오일",
                ing("정제수", "Water"), ing("라벤더오일", "Lavandula Angustifolia (Lavender) Oil")).avoid()).isTrue();
        assertThat(withReportCaution(null, "고함량 각질제거 산", ing("글라이콜릭애씨드", "Glycolic Acid")).avoid()).isTrue();
        assertThat(withReportCaution(null, "인공 향료", ing("향료", "Parfum")).cautions())
                .contains("리포트에서 주의가 필요하다고 한 성분 향료");
    }

    @Test
    void reportCautionDoesNotCatchHarmlessLookalikes() {
        // 식물성 오일은 에센셜 오일이 아니다
        assertThat(withReportCaution(null, "고농축 에센셜 오일", ing("호호바오일", "Simmondsia Chinensis (Jojoba) Seed Oil")).avoid()).isFalse();
        // "고함량" 묶음은 뒤쪽(소량) 성분이면 해당 없음: 11번째 젖산, 11번째 알코올
        ProductIngredient[] tail = new ProductIngredient[11];
        for (int i = 0; i < 10; i++) tail[i] = ing("정제수" + i, "Water " + i);
        tail[10] = ing("젖산", "Lactic Acid");
        assertThat(withReportCaution(null, "고함량 각질제거 산", tail).avoid()).isFalse();
        tail[10] = ing("에탄올", "Alcohol");
        assertThat(withReportCaution("high_alcohol_denat", "고함량 변성알코올", tail).avoid()).isFalse();
        // 주의 성분이 없는 리포트 묶음은 아무것도 빼지 않는다
        assertThat(withReportCaution("something_else", "기타 주의", ing("향료", "Fragrance")).avoid()).isFalse();
    }

    @Test
    void registeredAllergenSubtracts60AndComesFirst() {
        Profile p = new Profile("OSNT", List.of(), List.of(), List.of(" 판테놀 "));
        Result r = scorer.score(List.of(ing("판테놀", "Panthenol"), ing("병풀추출물", "Centella Asiatica Extract")), p);

        assertThat(r.allergen()).isTrue();
        assertThat(r.avoid()).isTrue();
        assertThat(r.score()).isEqualTo(20); // 50 + 30 − 60
        assertThat(r.cautions().getFirst()).isEqualTo("등록한 주의 성분 판테놀 포함");
    }

    @Test
    void scoreStaysWithinZeroTo99() {
        Profile p = new Profile("OSNT", List.of(new Recommended("Panthenol", "판테놀", 1.0)), List.of(), List.of("향료"));
        Result low = scorer.score(List.of(ing("향료", "Fragrance"), ing("변성알코올", "Alcohol Denat.")), p);
        Result high = scorer.score(List.of(ing("판테놀", "Panthenol"), ing("병풀추출물", "Centella Asiatica Extract")),
                new Profile("OSNT", List.of(new Recommended("Panthenol", "판테놀", 1.0)), List.of(), List.of()));

        assertThat(low.score()).isZero();
        assertThat(high.score()).isBetween(90, 99);
    }

    @Test
    void noBaumannMeansNoScore() {
        Result r = scorer.score(List.of(ing("판테놀", "Panthenol")), new Profile(null, List.of(), List.of(), List.of()));
        assertThat(r.score()).isNull();
    }
}
