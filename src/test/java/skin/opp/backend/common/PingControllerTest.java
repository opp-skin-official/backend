package skin.opp.backend.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import skin.opp.backend.user.TermsService;
import org.springframework.test.web.servlet.MockMvc;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.auth.SecurityConfig;

@WebMvcTest(PingController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class PingControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TermsService terms;

    @MockitoBean
    KakaoOAuth2UserService kakaoUserService;

    @Test
    void pingIsPublic() throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void allowsFrontendOriginWithCredentials() throws Exception {
        mvc.perform(get("/api/ping").header("Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void rejectsUnknownOrigin() throws Exception {
        mvc.perform(get("/api/ping").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }
}
