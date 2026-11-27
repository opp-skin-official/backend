package skin.opp.backend.aireport;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AiReportDtos {

    private AiReportDtos() {}

    /** 신고 사유: 불쾌·부적절 / 위험한 내용 / 진단·처방처럼 말함 / 사실과 다름 / 기타 */
    public enum Reason { OFFENSIVE, HARMFUL, MEDICAL, INACCURATE, OTHER }

    /** detail = 사용자가 덧붙인 설명 (선택) */
    public record ReportRequest(@NotNull Reason reason, @Size(max = 500) String detail) {}
}
