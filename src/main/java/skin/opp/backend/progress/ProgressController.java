package skin.opp.backend.progress;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.progress.ProgressDtos.RecordRequest;
import skin.opp.backend.progress.ProgressDtos.RecordView;

@RestController
@RequestMapping("/api/progress-records")
public class ProgressController {

    private final ProgressService service;

    public ProgressController(ProgressService service) {
        this.service = service;
    }

    @PutMapping("/{date}")
    public RecordView save(@AuthenticationPrincipal OAuth2User principal,
                           @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @Valid @RequestBody RecordRequest request) {
        return service.save(userId(principal), date, request);
    }

    @GetMapping
    public List<RecordView> list(@AuthenticationPrincipal OAuth2User principal,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(userId(principal), from, to);
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
