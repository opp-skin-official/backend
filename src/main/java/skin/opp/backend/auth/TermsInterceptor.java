package skin.opp.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import skin.opp.backend.user.TermsService;

/**
 * 약관(이용약관·개인정보 수집·이용)에 동의하기 전에는 /api/** 를 막는다(403).
 * 내 정보 확인(/api/me, 탈퇴 포함)과 동의(/api/me/terms), 서버 확인(/api/ping)만 열어 둔다.
 */
@Configuration
public class TermsInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final TermsService terms;

    public TermsInterceptor(TermsService terms) {
        this.terms = terms;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**")
                .excludePathPatterns("/api/me", "/api/me/terms", "/api/ping");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User user)) return true; // 로그인 검사는 SecurityConfig
        Object id = user.getAttribute(KakaoOAuth2UserService.USER_ID);
        if (id instanceof Number n && !terms.agreed(n.longValue())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "약관에 동의해야 이용할 수 있어요.");
        }
        return true;
    }
}
