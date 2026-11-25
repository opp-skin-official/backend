package skin.opp.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.user.User;
import skin.opp.backend.user.UserRepository;

@RestController
public class MeController {

    public record MeResponse(Long id, String nickname, String profileImageUrl) {}

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal OAuth2User principal) {
        Long userId = ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
        User user = users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new MeResponse(user.getId(), user.getNickname(), user.getProfileImageUrl());
    }
}
