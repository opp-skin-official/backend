package skin.opp.backend.consult;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.consult.ConsultDtos.ConsultRequest;
import skin.opp.backend.consult.ConsultDtos.ConsultView;
import skin.opp.backend.consult.ConsultDtos.FollowUpRequest;
import skin.opp.backend.routine.RoutineDtos.PlanView;

@RestController
@RequestMapping("/api/consultations")
public class ConsultController {

    private final ConsultService service;

    public ConsultController(ConsultService service) {
        this.service = service;
    }

    /** 문진 확정 → 리포트·루틴 생성 (AI 호출로 수십 초 걸릴 수 있음) */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultView create(@AuthenticationPrincipal OAuth2User principal, @Valid @RequestBody ConsultRequest request) {
        return service.create(userId(principal), request);
    }

    /** 리포트가 있는 가장 최근 상담 (없으면 404) */
    @GetMapping("/latest")
    public ConsultView latest(@AuthenticationPrincipal OAuth2User principal) {
        return service.latest(userId(principal));
    }

    /** 후속 상담: 이전 분석으로 지금 가능한 루틴만 다시 만든다 */
    @PostMapping("/{id}/follow-ups")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultView followUp(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id,
                                @Valid @RequestBody FollowUpRequest request) {
        return service.followUp(userId(principal), id, request);
    }

    @GetMapping("/{id}")
    public ConsultView get(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id) {
        return service.get(userId(principal), id);
    }

    @PostMapping("/{id}/routine")
    public ConsultView regenerateRoutine(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id) {
        return service.regenerateRoutine(userId(principal), id);
    }

    /** 리포트 루틴으로 관리 계획 시작 */
    @PostMapping("/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView apply(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id) {
        return service.applyRoutine(userId(principal), id);
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
