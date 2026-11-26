package skin.opp.backend.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 자체 AI 모델(Model A 분석, Model B 루틴·챗봇) 접속 설정. 키는 서버에서만 쓴다.
 * 시간 기본값은 "AI API v4.2 백엔드 연동 명세서" 5.2 권장값(connect 5s, read 60~90s).
 */
@ConfigurationProperties("app.ai")
public record AiProperties(
        Endpoint modelA,
        Endpoint modelB,
        @DefaultValue("5s") Duration connectTimeout,
        /* 응답 헤더(일반 호출은 본문)까지 기다리는 시간. 콜드 스타트(25~45초)를 포함해 90초. */
        @DefaultValue("90s") Duration readTimeout,
        /* 스트리밍 응답 전체에 허용하는 시간 */
        @DefaultValue("150s") Duration streamTimeout,
        /* 502/503/504·연결 실패 때 재시도 횟수 (명세: 지수 백오프, 최대 2회) */
        @DefaultValue("2") int maxRetries,
        @DefaultValue("1s") Duration retryBackoff) {

    public record Endpoint(String url, String apiKey) {
        boolean configured() {
            return url != null && !url.isBlank() && apiKey != null && !apiKey.isBlank();
        }
    }
}
