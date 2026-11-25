package skin.opp.backend.progress;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.progress.ProgressDtos.PerceivedChange;
import skin.opp.backend.progress.ProgressDtos.RecordRequest;
import skin.opp.backend.progress.ProgressDtos.RecordView;
import skin.opp.backend.progress.ProgressDtos.SkinCondition;

@Service
public class ProgressService {

    /** 지난 기록은 이 기간 안에서만 남기거나 고칠 수 있다. */
    static final int EDITABLE_DAYS = 30;
    /** 한 번에 조회할 수 있는 최대 기간 */
    static final int MAX_RANGE_DAYS = 92;

    private final JdbcClient jdbc;
    private final Clock clock;

    public ProgressService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 하루에 기록 하나. 같은 날 다시 남기면 고친다. */
    @Transactional
    public RecordView save(long userId, LocalDate date, RecordRequest request) {
        LocalDate today = LocalDate.now(clock);
        if (date.isAfter(today)) {
            throw badRequest("아직 오지 않은 날은 기록할 수 없어요.");
        }
        if (date.isBefore(today.minusDays(EDITABLE_DAYS))) {
            throw badRequest("최근 " + EDITABLE_DAYS + "일 안의 기록만 남길 수 있어요.");
        }
        String memo = request.memo() == null || request.memo().isBlank() ? null : request.memo().strip();
        jdbc.sql("""
                INSERT INTO progress_records (user_id, record_date, perceived_change, skin_condition, memo)
                VALUES (:u, :d, :change, :condition, :memo)
                ON CONFLICT (user_id, record_date) DO UPDATE SET
                    perceived_change = EXCLUDED.perceived_change,
                    skin_condition = EXCLUDED.skin_condition,
                    memo = EXCLUDED.memo,
                    updated_at = now()
                """)
                .param("u", userId).param("d", date)
                .param("change", request.perceivedChange().name())
                .param("condition", request.skinCondition().name())
                .param("memo", memo)
                .update();
        return new RecordView(date, request.perceivedChange(), request.skinCondition(), memo);
    }

    public List<RecordView> list(long userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw badRequest("기간이 올바르지 않아요.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw badRequest("한 번에 " + MAX_RANGE_DAYS + "일까지 조회할 수 있어요.");
        }
        return jdbc.sql("""
                SELECT record_date, perceived_change, skin_condition, memo FROM progress_records
                WHERE user_id = :u AND record_date BETWEEN :from AND :to ORDER BY record_date
                """).param("u", userId).param("from", from).param("to", to)
                .query((rs, n) -> new RecordView(
                        rs.getObject("record_date", LocalDate.class),
                        PerceivedChange.valueOf(rs.getString("perceived_change")),
                        SkinCondition.valueOf(rs.getString("skin_condition")),
                        rs.getString("memo")))
                .list();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
