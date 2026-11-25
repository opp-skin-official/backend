package skin.opp.backend.skintype;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.skintype.SurveyDtos.AssessmentView;
import skin.opp.backend.skintype.SurveyDtos.SubmitRequest;
import skin.opp.backend.skintype.SurveyDtos.SurveyView;

@RestController
@RequestMapping("/api/skin-survey")
public class SkinSurveyController {

    private final SkinSurveyService service;

    public SkinSurveyController(SkinSurveyService service) {
        this.service = service;
    }

    @GetMapping
    public SurveyView survey() {
        return service.survey();
    }

    @PostMapping("/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssessmentView submit(@AuthenticationPrincipal OAuth2User principal, @Valid @RequestBody SubmitRequest request) {
        return service.submit(userId(principal), request);
    }

    @GetMapping("/assessments/latest")
    public AssessmentView latest(@AuthenticationPrincipal OAuth2User principal) {
        return service.latest(userId(principal))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "아직 설문 결과가 없습니다."));
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
