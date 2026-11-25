package skin.opp.backend.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.User;
import skin.opp.backend.user.UserService;

/**
 * 카카오 사용자 정보를 받아 우리 사용자로 저장하고, 세션에는 우리 사용자 ID를 담는다.
 * 세션(JDBC)에 직렬화되므로 속성 값은 String/Long만 쓴다.
 */
@Service
public class KakaoOAuth2UserService extends DefaultOAuth2UserService {

    public static final String USER_ID = "userId";

    private final UserService userService;

    public KakaoOAuth2UserService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User kakaoUser = super.loadUser(request);
        return toPrincipal(userService.upsertFromKakao(KakaoProfile.from(kakaoUser.getAttributes())));
    }

    static OAuth2User toPrincipal(User user) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(USER_ID, user.getId());
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, USER_ID);
    }
}
