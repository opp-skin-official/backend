package skin.opp.backend.skintype;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 채점 규칙 (Notion "군 장병용 피부 유형 진단 설문" 1절)
 * <ul>
 *   <li>선택지 ①~④ = 1~4점</li>
 *   <li>축 점수 = (응답 점수 합 − n) ÷ (3 × n) × 100, n = 그 축에서 응답한 문항 수</li>
 *   <li>50점 이상이면 앞 글자, 미만이면 뒤 글자</li>
 *   <li>40~60점이면 경계(표시용, 판정은 그대로)</li>
 * </ul>
 * 부동소수 오차를 피하려고 비교는 정수식으로 한다.
 */
public final class SkinTypeScorer {

    public record Answer(Axis axis, int score) {
        public Answer {
            if (score < 1 || score > 4) {
                throw new IllegalArgumentException("점수는 1~4여야 합니다: " + score);
            }
        }
    }

    public record AxisResult(Axis axis, int sum, int answered, double score, char letter, boolean borderline) {}

    public record Result(Map<Axis, AxisResult> axes) {
        /** 내부 참고용 설문 코드. 예: OSNT-L */
        public String surveyCode() {
            StringBuilder code = new StringBuilder();
            for (Axis axis : List.of(Axis.OIL, Axis.SENSITIVITY, Axis.PIGMENTATION, Axis.WT)) {
                code.append(axes.get(axis).letter());
            }
            return code.append('-').append(axes.get(Axis.CARE_STYLE).letter()).toString();
        }

        public boolean hasBorderline() {
            return axes.values().stream().anyMatch(AxisResult::borderline);
        }

        /** 32유형 번호(1~32). 축 순서 유분 → 민감도 → 자국 → W/T → 관리 성향, 앞 글자가 먼저. */
        public int typeNumber() {
            int index = 0;
            for (Axis axis : Axis.values()) {
                index = index * 2 + (axes.get(axis).letter() == axis.front() ? 0 : 1);
            }
            return index + 1;
        }

        public String typeCode() {
            return String.format("TYPE_%02d", typeNumber());
        }
    }

    private SkinTypeScorer() {}

    public static Result score(List<Answer> answers) {
        Map<Axis, int[]> totals = new EnumMap<>(Axis.class);
        for (Answer answer : answers) {
            int[] t = totals.computeIfAbsent(answer.axis(), a -> new int[2]);
            t[0] += answer.score();
            t[1]++;
        }
        Map<Axis, AxisResult> results = new EnumMap<>(Axis.class);
        for (Axis axis : Axis.values()) {
            int[] t = totals.get(axis);
            if (t == null) {
                throw new IllegalArgumentException("응답이 없는 축이 있습니다: " + axis);
            }
            results.put(axis, axisResult(axis, t[0], t[1]));
        }
        return new Result(results);
    }

    static AxisResult axisResult(Axis axis, int sum, int n) {
        int above = sum - n; // 0 ~ 3n
        boolean front = 2 * above >= 3 * n;                       // score >= 50
        boolean borderline = 5 * above >= 6 * n && 5 * above <= 9 * n; // 40 <= score <= 60
        double score = Math.round(above * 1000.0 / (3 * n)) / 10.0;
        return new AxisResult(axis, sum, n, score, front ? axis.front() : axis.back(), borderline);
    }
}
