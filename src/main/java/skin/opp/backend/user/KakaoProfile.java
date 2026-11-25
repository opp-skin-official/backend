package skin.opp.backend.user;

import java.util.Map;

/** 카카오 사용자 정보 API(/v2/user/me) 응답에서 쓰는 값. */
public record KakaoProfile(Long kakaoId, String nickname, String profileImageUrl, String email) {

    @SuppressWarnings("unchecked")
    public static KakaoProfile from(Map<String, Object> attributes) {
        Object id = attributes.get("id");
        if (!(id instanceof Number number)) {
            throw new IllegalArgumentException("카카오 응답에 id가 없습니다.");
        }
        Map<String, Object> account =
                (Map<String, Object>) attributes.getOrDefault("kakao_account", Map.of());
        Map<String, Object> profile = (Map<String, Object>) account.getOrDefault("profile", Map.of());
        return new KakaoProfile(
                number.longValue(),
                (String) profile.get("nickname"),
                (String) profile.get("profile_image_url"),
                (String) account.get("email"));
    }
}
