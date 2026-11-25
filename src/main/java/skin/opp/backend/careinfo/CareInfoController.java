package skin.opp.backend.careinfo;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.careinfo.CareInfoDtos.AlertRequest;
import skin.opp.backend.careinfo.CareInfoDtos.AlertView;
import skin.opp.backend.careinfo.CareInfoDtos.CareInfoView;
import skin.opp.backend.careinfo.CareInfoDtos.EnvironmentRequest;

@RestController
@RequestMapping("/api/care-info")
public class CareInfoController {

    private final CareInfoService service;

    public CareInfoController(CareInfoService service) {
        this.service = service;
    }

    @GetMapping
    public CareInfoView get(@AuthenticationPrincipal OAuth2User principal) {
        return service.get(userId(principal));
    }

    @PutMapping("/environment")
    public CareInfoView saveEnvironment(
            @AuthenticationPrincipal OAuth2User principal, @Valid @RequestBody EnvironmentRequest request) {
        return service.saveEnvironment(userId(principal), request);
    }

    @PostMapping("/alerts")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertView addAlert(@AuthenticationPrincipal OAuth2User principal, @Valid @RequestBody AlertRequest request) {
        return service.addAlert(userId(principal), request.target());
    }

    @DeleteMapping("/alerts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAlert(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id) {
        service.deleteAlert(userId(principal), id);
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
