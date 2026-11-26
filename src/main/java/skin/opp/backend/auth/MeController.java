package skin.opp.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.user.AccountDeletionService;
import skin.opp.backend.user.User;
import skin.opp.backend.user.UserRepository;

@RestController
public class MeController {

    public record MeResponse(Long id, String nickname, String profileImageUrl) {}

    private final UserRepository users;
    private final AccountDeletionService deletion;

    public MeController(UserRepository users, AccountDeletionService deletion) {
        this.users = users;
        this.deletion = deletion;
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal OAuth2User principal) {
        Long userId = ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
        User user = users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new MeResponse(user.getId(), user.getNickname(), user.getProfileImageUrl());
    }

    /** 회원 탈퇴: 모든 기록을 바로 지우고 로그아웃한다(되돌릴 수 없음). */
    @DeleteMapping("/api/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal OAuth2User principal, Authentication authentication,
                       HttpServletRequest request, HttpServletResponse response) {
        deletion.delete(((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue());
        // 지금 세션도 무효화해 응답 끝에 세션이 다시 저장되지 않게 한다.
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}
