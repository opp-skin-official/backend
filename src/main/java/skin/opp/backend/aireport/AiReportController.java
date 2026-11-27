package skin.opp.backend.aireport;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import skin.opp.backend.aireport.AiReportDtos.ReportRequest;
import skin.opp.backend.auth.KakaoOAuth2UserService;

/** AI가 만든 내용 신고 (Google Play 생성형 AI 정책) */
@RestController
public class AiReportController {

    private final AiReportService service;

    public AiReportController(AiReportService service) {
        this.service = service;
    }

    /** 채팅 답변 신고 */
    @PostMapping("/api/conversations/{id}/messages/{messageId}/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportMessage(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id,
                              @PathVariable long messageId, @Valid @RequestBody ReportRequest request) {
        service.reportMessage(userId(principal), id, messageId, request);
    }

    /** 상담 관리 리포트 신고 */
    @PostMapping("/api/consultations/{id}/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportConsultation(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id,
                                   @Valid @RequestBody ReportRequest request) {
        service.reportConsultation(userId(principal), id, request);
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
