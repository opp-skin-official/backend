package skin.opp.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KakaoProfileTest {

    @Test
    void readsIdNicknameImageAndEmail() {
        Map<String, Object> attributes = Map.of(
                "id", 123456789L,
                "kakao_account", Map.of(
                        "email", "user@example.com",
                        "profile", Map.of(
                                "nickname", "김멋사",
                                "profile_image_url", "https://k.kakaocdn.net/p.jpg")));

        KakaoProfile profile = KakaoProfile.from(attributes);

        assertThat(profile).isEqualTo(new KakaoProfile(
                123456789L, "김멋사", "https://k.kakaocdn.net/p.jpg", "user@example.com"));
    }

    @Test
    void toleratesMissingOptionalFields() {
        KakaoProfile profile = KakaoProfile.from(Map.of("id", 42));

        assertThat(profile).isEqualTo(new KakaoProfile(42L, null, null, null));
    }

    @Test
    void rejectsResponseWithoutId() {
        assertThatThrownBy(() -> KakaoProfile.from(Map.of("kakao_account", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
