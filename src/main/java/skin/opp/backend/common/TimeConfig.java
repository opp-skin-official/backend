package skin.opp.backend.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 날짜 계산(오늘, D-day, 이번 주)은 한국 시간 기준. 테스트에서는 고정 시계로 바꾼다. */
@Configuration
public class TimeConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    Clock clock() {
        return Clock.system(KST);
    }
}
