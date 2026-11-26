package skin.opp.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import skin.opp.backend.user.AccountDeletionService;
import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.User;
import skin.opp.backend.user.UserRepository;

@WebMvcTest(MeController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AuthEndpointsTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    KakaoOAuth2UserService kakaoUserService;

    @MockitoBean
    UserRepository users;

    @MockitoBean
    AccountDeletionService accountDeletion; // 탈퇴는 AccountDeletionIntegrationTest에서 확인

    private static User savedUser(long id, String nickname) {
        User user = User.fromKakao(new KakaoProfile(1000L + id, nickname, "https://img.example/p.jpg", null));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void meWithoutLoginReturns401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsLoggedInUser() throws Exception {
        given(users.findById(7L)).willReturn(Optional.of(savedUser(7L, "김멋사")));

        mvc.perform(get("/api/me").with(oauth2Login().oauth2User(
                        KakaoOAuth2UserService.toPrincipal(savedUser(7L, "김멋사")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.nickname").value("김멋사"))
                .andExpect(jsonPath("$.profileImageUrl").value("https://img.example/p.jpg"));
    }

    @Test
    void meReturns401WhenUserNoLongerExists() throws Exception {
        given(users.findById(7L)).willReturn(Optional.empty());

        mvc.perform(get("/api/me").with(oauth2Login().oauth2User(
                        KakaoOAuth2UserService.toPrincipal(savedUser(7L, "김멋사")))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRedirectsToKakaoWithExpectedParameters() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("https://kauth.kakao.com/oauth/authorize?");
        Map<String, String> params = UriComponentsBuilder.fromUriString(location).build()
                .getQueryParams().toSingleValueMap();
        assertThat(params)
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "test-kakao-client-id")
                .containsKey("state");
        assertThat(params.get("scope")).isIn("profile_nickname%20profile_image", "profile_nickname,profile_image");
        assertThat(params.get("redirect_uri")).endsWith("/login/oauth2/code/kakao");
    }

    @Test
    void logoutRequiresCsrfToken() throws Exception {
        mvc.perform(post("/api/logout").with(oauth2Login())).andExpect(status().isForbidden());
    }

    @Test
    void logoutWithCsrfReturns204() throws Exception {
        mvc.perform(post("/api/logout").with(oauth2Login()).with(csrf()))
                .andExpect(status().isNoContent());
    }
}
