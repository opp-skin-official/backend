package skin.opp.backend.skintype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import skin.opp.backend.skintype.SkinTypeScorer.Answer;
import skin.opp.backend.skintype.SkinTypeScorer.AxisResult;

class SkinTypeScorerTest {

    /** Notion 1.3 판정표 (5문항 모두 응답) */
    @ParameterizedTest(name = "합 {0} → {1}점, 앞 글자={2}, 경계={3}")
    @CsvSource({
        "5, 0.0, false, false",
        "10, 33.3, false, false",
        "11, 40.0, false, true",
        "12, 46.7, false, true",
        "13, 53.3, true, true",
        "14, 60.0, true, true",
        "15, 66.7, true, false",
        "20, 100.0, true, false",
    })
    void matchesNotionTableForFiveAnswers(int sum, double score, boolean front, boolean borderline) {
        AxisResult r = SkinTypeScorer.axisResult(Axis.OIL, sum, 5);
        assertThat(r.score()).isEqualTo(score);
        assertThat(r.letter()).isEqualTo(front ? 'O' : 'D');
        assertThat(r.borderline()).isEqualTo(borderline);
    }

    @Test
    void everySumFrom5To20FollowsThreshold() {
        for (int sum = 5; sum <= 20; sum++) {
            AxisResult r = SkinTypeScorer.axisResult(Axis.SENSITIVITY, sum, 5);
            assertThat(r.letter()).as("sum %d", sum).isEqualTo(sum >= 13 ? 'S' : 'R');
            assertThat(r.borderline()).as("sum %d", sum).isEqualTo(sum >= 11 && sum <= 14);
        }
    }

    @Test
    void fourAnswersCanScoreExactlyFiftyAndThatIsFrontLetter() {
        // 민감 문항(14, 18번)을 빼면 n=4: 합 10 → (10-4)/12*100 = 50점
        AxisResult r = SkinTypeScorer.axisResult(Axis.PIGMENTATION, 10, 4);
        assertThat(r.score()).isEqualTo(50.0);
        assertThat(r.letter()).isEqualTo('P');
        assertThat(r.borderline()).isTrue();
    }

    @Test
    void buildsSurveyCodeAndTypeCode() {
        List<Answer> answers = new ArrayList<>();
        addAxis(answers, Axis.OIL, 4);          // O
        addAxis(answers, Axis.SENSITIVITY, 4);  // S
        addAxis(answers, Axis.PIGMENTATION, 1); // N
        addAxis(answers, Axis.WT, 1);           // T
        addAxis(answers, Axis.CARE_STYLE, 1);   // L

        SkinTypeScorer.Result result = SkinTypeScorer.score(answers);

        assertThat(result.surveyCode()).isEqualTo("OSNT-L");
        // O S N T L → 0,0,1,1,1 → 7 → TYPE_08 (docs/data-model.md 매핑표)
        assertThat(result.typeCode()).isEqualTo("TYPE_08");
        assertThat(result.hasBorderline()).isFalse();
    }

    @Test
    void allThirtyTwoCombinationsMapToDistinctCodes() {
        Set<String> codes = new HashSet<>();
        for (int mask = 0; mask < 32; mask++) {
            List<Answer> answers = new ArrayList<>();
            int bit = 16;
            for (Axis axis : Axis.values()) {
                addAxis(answers, axis, (mask & bit) == 0 ? 4 : 1);
                bit >>= 1;
            }
            SkinTypeScorer.Result r = SkinTypeScorer.score(answers);
            assertThat(r.typeNumber()).isEqualTo(mask + 1);
            codes.add(r.typeCode());
        }
        assertThat(codes).hasSize(32).contains("TYPE_01", "TYPE_32");
    }

    @Test
    void rejectsMissingAxisAndOutOfRangeScore() {
        assertThatThrownBy(() -> SkinTypeScorer.score(List.of(new Answer(Axis.OIL, 3))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Answer(Axis.OIL, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void addAxis(List<Answer> answers, Axis axis, int score) {
        for (int i = 0; i < 5; i++) {
            answers.add(new Answer(axis, score));
        }
    }
}
