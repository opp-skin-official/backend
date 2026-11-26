package skin.opp.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사용자별 AI 호출 횟수 제한 (최근 24시간). 모델 비용이 사용량에 비례하므로 한 사람이 계속 보내지 못하게 한다.
 * 모델을 부르기 직전에 {@link #acquire}로 한 번 쓴다. 실패한 호출도 모델 자원을 쓰므로 센다.
 */
@Component
@EnableConfigurationProperties(AiQuota.Limits.class)
public class AiQuota {

    public enum Kind { CHAT, CONSULT }

    /** 24시간에 허용하는 횟수. 0 이하면 제한 없음 */
    @ConfigurationProperties("app.ai.limit")
    public record Limits(@DefaultValue("50") int chatPerDay, @DefaultValue("10") int consultPerDay) {
        int of(Kind kind) {
            return kind == Kind.CHAT ? chatPerDay : consultPerDay;
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Limits limits;

    public AiQuota(JdbcClient jdbc, TransactionTemplate tx, Limits limits) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.limits = limits;
    }

    /** 남은 횟수가 있으면 1회를 기록하고, 없으면 429 */
    public void acquire(long userId, Kind kind) {
        int limit = limits.of(kind);
        if (limit <= 0) return;
        boolean ok = Boolean.TRUE.equals(tx.execute(s -> {
            // 같은 사용자의 동시 요청이 함께 통과하지 않게 사용자 단위로 잠근다(트랜잭션 끝나면 풀림).
            jdbc.sql("SELECT pg_advisory_xact_lock(:k, CAST(:u AS integer))")
                    .param("k", 7301).param("u", (int) userId).query().singleRow();
            long used = jdbc.sql("""
                    SELECT count(*) FROM ai_calls
                    WHERE user_id = :u AND kind = :kind AND created_at > now() - interval '24 hours'
                    """).param("u", userId).param("kind", kind.name()).query(Long.class).single();
            if (used >= limit) return false;
            jdbc.sql("INSERT INTO ai_calls (user_id, kind) VALUES (:u, :kind)")
                    .param("u", userId).param("kind", kind.name()).update();
            return true;
        }));
        if (!ok) {
            String what = kind == Kind.CHAT ? "질문" : "AI 상담";
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    what + "은 하루(24시간)에 " + limit + "번까지 할 수 있어요. 나중에 다시 이용해 주세요.");
        }
    }
}
