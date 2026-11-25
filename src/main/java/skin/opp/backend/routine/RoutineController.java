package skin.opp.backend.routine;

import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.routine.RoutineDtos.PlanRequest;
import skin.opp.backend.routine.RoutineDtos.PlanView;
import skin.opp.backend.routine.RoutineDtos.TodayView;

@RestController
public class RoutineController {

    private final RoutineService service;

    public RoutineController(RoutineService service) {
        this.service = service;
    }

    @PostMapping("/api/care-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView createPlan(@AuthenticationPrincipal OAuth2User principal, @Valid @RequestBody PlanRequest request) {
        return service.createPlan(userId(principal), request);
    }

    @GetMapping("/api/care-plans/current")
    public PlanView currentPlan(@AuthenticationPrincipal OAuth2User principal) {
        return service.currentPlan(userId(principal)).orElseThrow(() -> notFound("진행 중인 관리 계획이 없어요."));
    }

    @GetMapping("/api/routines/today")
    public TodayView today(@AuthenticationPrincipal OAuth2User principal) {
        return service.today(userId(principal)).orElseThrow(() -> notFound("진행 중인 관리 계획이 없어요."));
    }

    @PutMapping("/api/routine-items/{itemId}/checks/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void check(@AuthenticationPrincipal OAuth2User principal, @PathVariable long itemId,
                      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.setChecked(userId(principal), itemId, date, true);
    }

    @DeleteMapping("/api/routine-items/{itemId}/checks/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uncheck(@AuthenticationPrincipal OAuth2User principal, @PathVariable long itemId,
                        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.setChecked(userId(principal), itemId, date, false);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
