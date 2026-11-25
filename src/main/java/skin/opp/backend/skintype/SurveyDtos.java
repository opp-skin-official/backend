package skin.opp.backend.skintype;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class SurveyDtos {

    private SurveyDtos() {}

    public record OptionView(int position, String label) {}

    public record QuestionView(
            int number, Axis axis, String text, boolean image, boolean sensitive, List<OptionView> options) {}

    public record SurveyView(int version, List<QuestionView> questions) {}

    /** answers: 문항 번호 → 선택한 선택지 위치(1~4) */
    public record SubmitRequest(
            @NotNull Integer version, boolean sensitiveConsent, @NotNull Map<Integer, Integer> answers) {}

    public record AxisView(Axis axis, char letter, double score, boolean borderline) {}

    public record AssessmentView(
            long id,
            String typeCode,
            String title,
            List<String> skinTags,
            List<String> environmentTags,
            String careStyle,
            String careStyleLabel,
            List<AxisView> axes,
            LocalDate retestRecommendedAt,
            Instant createdAt) {}
}
