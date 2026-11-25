package skin.opp.backend.careinfo;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.careinfo.CareInfoDtos.AlertView;
import skin.opp.backend.careinfo.CareInfoDtos.AllergyExperience;
import skin.opp.backend.careinfo.CareInfoDtos.CareInfoView;
import skin.opp.backend.careinfo.CareInfoDtos.EnvironmentRequest;
import skin.opp.backend.careinfo.CareInfoDtos.EnvironmentView;
import skin.opp.backend.careinfo.CareInfoDtos.OutdoorTraining;
import skin.opp.backend.careinfo.CareInfoDtos.WashTime;

@Service
public class CareInfoService {

    static final int MAX_ALERTS = 20;

    private final JdbcClient jdbc;

    public CareInfoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CareInfoView get(long userId) {
        EnvironmentView environment = jdbc.sql("""
                SELECT wash_time, outdoor_training, allergy_experience FROM service_environments WHERE user_id = :u
                """).param("u", userId)
                .query((rs, i) -> new EnvironmentView(
                        WashTime.valueOf(rs.getString("wash_time")),
                        OutdoorTraining.valueOf(rs.getString("outdoor_training")),
                        AllergyExperience.valueOf(rs.getString("allergy_experience"))))
                .optional().orElse(null);
        return new CareInfoView(environment, alerts(userId));
    }

    @Transactional
    public CareInfoView saveEnvironment(long userId, EnvironmentRequest request) {
        jdbc.sql("""
                INSERT INTO service_environments (user_id, wash_time, outdoor_training, allergy_experience, updated_at)
                VALUES (:u, :wash, :training, :allergy, now())
                ON CONFLICT (user_id) DO UPDATE SET
                    wash_time = EXCLUDED.wash_time,
                    outdoor_training = EXCLUDED.outdoor_training,
                    allergy_experience = EXCLUDED.allergy_experience,
                    updated_at = now()
                """)
                .param("u", userId)
                .param("wash", request.washTime().name())
                .param("training", request.outdoorTraining().name())
                .param("allergy", request.allergyExperience().name())
                .update();
        return get(userId);
    }

    @Transactional
    public AlertView addAlert(long userId, String rawTarget) {
        String target = rawTarget.strip();
        if (target.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제품명 또는 성분을 입력해 주세요.");
        }
        long count = jdbc.sql("SELECT count(*) FROM skin_alerts WHERE user_id = :u").param("u", userId)
                .query(Long.class).single();
        if (count >= MAX_ALERTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "주의 성분은 " + MAX_ALERTS + "개까지 등록할 수 있어요.");
        }
        // 같은 이름을 다시 등록하면 기존 항목을 돌려준다.
        return jdbc.sql("""
                INSERT INTO skin_alerts (user_id, target) VALUES (:u, :t)
                ON CONFLICT (user_id, target) DO UPDATE SET target = EXCLUDED.target
                RETURNING id, target
                """).param("u", userId).param("t", target)
                .query((rs, i) -> new AlertView(rs.getLong("id"), rs.getString("target"))).single();
    }

    @Transactional
    public void deleteAlert(long userId, long alertId) {
        int deleted = jdbc.sql("DELETE FROM skin_alerts WHERE id = :id AND user_id = :u")
                .param("id", alertId).param("u", userId).update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private List<AlertView> alerts(long userId) {
        return jdbc.sql("SELECT id, target FROM skin_alerts WHERE user_id = :u ORDER BY created_at, id")
                .param("u", userId)
                .query((rs, i) -> new AlertView(rs.getLong("id"), rs.getString("target"))).list();
    }
}
