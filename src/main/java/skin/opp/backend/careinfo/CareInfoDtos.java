package skin.opp.backend.careinfo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class CareInfoDtos {

    private CareInfoDtos() {}

    /** 하루 중 세안이 가능한 시간 */
    public enum WashTime { MORNING_EVENING, EVENING_ONLY, VARIES, NO_FIXED_TIME }

    /** 야외훈련 빈도 */
    public enum OutdoorTraining { ALMOST_DAILY, TWO_TO_THREE_PER_WEEK, WEEKLY_OR_LESS, IRREGULAR }

    /** 특정 제품·성분에 알레르기·자극 경험 */
    public enum AllergyExperience { YES, NO, UNKNOWN }

    public record EnvironmentView(WashTime washTime, OutdoorTraining outdoorTraining, AllergyExperience allergyExperience) {}

    public record EnvironmentRequest(
            @NotNull WashTime washTime, @NotNull OutdoorTraining outdoorTraining,
            @NotNull AllergyExperience allergyExperience) {}

    public record AlertView(long id, String target) {}

    public record AlertRequest(@NotBlank @Size(max = 100) String target) {}

    /** environment는 아직 입력하지 않았으면 null */
    public record CareInfoView(EnvironmentView environment, List<AlertView> alerts) {}
}
