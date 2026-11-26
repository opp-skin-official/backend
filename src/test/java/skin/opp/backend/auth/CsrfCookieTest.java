package skin.opp.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import skin.opp.backend.common.PingController;

/** 운영 설정(상위 도메인, Secure)으로 XSRF-TOKEN 쿠키가 나가는지 확인한다. */
@WebMvcTest(PingController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.cookie.domain=example.com", "app.cookie.secure=true"})
class CsrfCookieTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    KakaoOAuth2UserService kakaoUserService;

    @Test
    void csrfCookieUsesParentDomainAndSecure() throws Exception {
        var response = mvc.perform(get("/api/ping")).andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("XSRF-TOKEN=")
                .contains("Domain=example.com")
                .contains("Secure")
                .contains("Path=/")
                .doesNotContain("HttpOnly"); // 프론트 스크립트가 읽어야 한다.
        // MockMvc 헤더 문자열에는 SameSite가 빠지므로 쿠키 속성으로 확인한다(실제 서버 헤더는 Docker 실행으로 확인).
        assertThat(response.getCookie("XSRF-TOKEN").getAttribute("SameSite")).isEqualTo("Lax");
    }
}
