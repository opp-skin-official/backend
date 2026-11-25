package skin.opp.backend.progress;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public final class ProgressDtos {

    private ProgressDtos() {}

    /** IA "경과 기록 기준"의 기록한 변화 */
    public enum PerceivedChange { BETTER, SAME, WORSE, UNSURE }

    /** Figma "내가 기록한 피부 컨디션": 편안 / 보통 / 불편 */
    public enum SkinCondition { COMFORT, NORMAL, UNCOMFORTABLE }

    public record RecordRequest(
            @NotNull PerceivedChange perceivedChange,
            @NotNull SkinCondition skinCondition,
            @Size(max = 500) String memo) {}

    public record RecordView(LocalDate date, PerceivedChange perceivedChange, SkinCondition skinCondition, String memo) {}
}
