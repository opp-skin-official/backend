package skin.opp.backend.user;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 서비스 이용에 필요한 약관 동의(이용약관, 개인정보 수집·이용). 현재 버전에 동의해야 API를 쓸 수 있다(TermsInterceptor).
 * 약관 본문을 바꾸면 {@link #CURRENT_VERSION}을 올리고 프론트 약관 화면(src/app/terms)의 버전도 같이 바꾼다 → 모두 다시 동의.
 */
@Service
public class TermsService {

    public static final String CURRENT_VERSION = "2026-11-26";

    /** 필수 동의 항목 (consents.type) */
    public static final List<String> REQUIRED = List.of("TERMS", "PRIVACY");

    private final JdbcClient jdbc;

    public TermsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 필수 항목 모두 현재 버전에 동의했는지 */
    public boolean agreed(long userId) {
        long count = jdbc.sql("""
                SELECT count(DISTINCT type) FROM consents
                WHERE user_id = :u AND type IN (:types) AND version = :v AND revoked_at IS NULL
                """).param("u", userId).param("types", REQUIRED).param("v", CURRENT_VERSION).query(Long.class).single();
        return count == REQUIRED.size();
    }

    /** 화면에 보여 준 버전으로 필수 항목에 모두 동의한다. 그사이 약관이 바뀌었으면 409. */
    @Transactional
    public void agree(long userId, String version) {
        if (!CURRENT_VERSION.equals(version)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "약관이 새로 바뀌었어요. 화면을 새로 고쳐 다시 확인해 주세요.");
        }
        if (agreed(userId)) return;
        for (String type : REQUIRED) {
            jdbc.sql("INSERT INTO consents (user_id, type, version) VALUES (:u, :t, :v)")
                    .param("u", userId).param("t", type).param("v", version).update();
        }
    }
}
