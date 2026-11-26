package skin.opp.backend.consult;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 문진 상담 값. 고민·부위·시작·기간·변화는 Model A 명세 값과 1:1로 맞춘다(소문자로 보냄, 부위는 그대로).
 * 화면 문구는 프론트가 정한다.
 */
public final class ConsultDtos {

    private ConsultDtos() {}

    public enum ConcernKind {
        ACNE_COMEDONAL_INFLAMMATORY, POST_ACNE_SCAR_ERYTHEMA_PORES, CONTACT_IRRITANT_DERMATITIS,
        DRYNESS_BARRIER_DAMAGE, FOLLICULITIS_SHAVING, UV_PIGMENTATION_PHOTOAGING,
        ATOPIC_DERMATITIS, SEBORRHEIC_DERMATITIS, SKIN_IMPROVEMENT_MAINTENANCE
    }

    /** skin3d.v2.Region (본인 기준 좌우) */
    public enum Region { FOREHEAD, CHEEK_LEFT, CHEEK_RIGHT, NOSE, CHIN, EYE_AREA }

    public enum Onset { IMMEDIATE_ACUTE, TRAINING_FIELD, DURING_SERVICE, POST_PRODUCT_SHAVE, CHRONIC_PRE_ENLISTMENT, OTHER_UNSPECIFIED }

    public enum Duration { ACUTE_DAYS, WEEKS, MONTHS, SERVICE_ONGOING, CHRONIC_YEARS }

    public enum Change { WORSENED, IMPROVED, UNCHANGED }

    /** 오늘 활동·자극 (military.training / irritants 문구로 보냄) */
    public enum Activity { OUTDOOR_TRAINING, MARCH, GAS_MASK, CAMO_CREAM, HELMET_STRAP, SHAVING }

    /** 관리 가능 시간 (military.care_time) */
    public enum CareTime { MORNING_EVENING, EVENING_ONLY, MINIMAL }

    /** 사용 가능한 제품 (military.hygiene_product_access) */
    public enum Product { CLEANSER, MOISTURIZER, SUNSCREEN, ALL_IN_ONE }

    public enum State { COMPLETED, REFERRED }

    /**
     * C-02에서 확정한 문진. redFlag가 true면 AI를 부르지 않고 의료진 안내로 끝내므로 careTime이 없어도 된다.
     * targetDays: 0이면 일반 루틴, 1~365면 기간별 루틴(Model B target_days).
     */
    public record ConsultRequest(
            @NotNull ConcernKind kind,
            @NotEmpty Set<Region> regions,
            @NotNull Onset onset,
            @NotNull Duration duration,
            @Min(1) @Max(5) int severity,
            @NotNull Change change,
            boolean redFlag,
            @Size(max = 10) List<Activity> activities,
            CareTime careTime,
            @Size(max = 10) List<Product> products,
            @Min(0) @Max(365) int targetDays) {

        public List<Activity> activitiesOrEmpty() {
            return activities == null ? List.of() : activities;
        }

        public List<Product> productsOrEmpty() {
            return products == null ? List.of() : products;
        }
    }

    /** 후속 상담: 고민·증상은 이전 상담 그대로, 지금 가능한 관리 시간·제품·목표 기간만 새로 받는다. */
    public record FollowUpRequest(
            @NotNull CareTime careTime,
            @Size(max = 10) List<Product> products,
            @Min(0) @Max(365) int targetDays) {}

    public record PairView(List<String> regions, String observation, String cause, String solution, String confidence) {}

    public record IngredientView(String key, String labelKo, String inci) {}

    public record PhaseView(
            String label, String dayRange, String focus,
            List<String> morning, List<String> evening, List<String> postActivity, String checkpoint) {}

    public record RoutineView(String mode, int targetDays, String summary, List<PhaseView> phases,
                              boolean referToClinician, String referralNotice) {}

    /** routine이 null이면 루틴을 만들지 못한 것(다시 만들 수 있음) */
    public record ReportView(
            List<PairView> pairs, boolean referToClinician, String notice, String referralNotice,
            List<IngredientView> recommendedIngredients, List<IngredientView> cautionIngredients,
            RoutineView routine, Long appliedPlanId) {}

    /** report는 REFERRED면 null. followsId = 이어서 한 이전 상담(후속 상담일 때) */
    public record ConsultView(long id, State state, String title, ConsultRequest answers,
                              OffsetDateTime createdAt, ReportView report, Long followsId) {}
}
