package skin.opp.backend.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
@Import(WebConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:3000")
class PingControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pingReturnsOk() throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void allowsFrontendOrigin() throws Exception {
        mvc.perform(get("/api/ping").header("Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void rejectsUnknownOrigin() throws Exception {
        mvc.perform(get("/api/ping").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }
}
