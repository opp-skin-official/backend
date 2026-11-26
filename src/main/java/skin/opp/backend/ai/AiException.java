package skin.opp.backend.ai;

/** 자체 AI 호출 실패. 원인 코드는 명세서 5.1의 error.code와 우리 쪽 사유(시간 초과, 안전 검사 차단 등)를 합친 것. */
public class AiException extends RuntimeException {

    public enum Code {
        /** 400/422: 요청 값이 잘못됨. 재시도하지 않는다. */
        INVALID_INPUT,
        /** 401: 키 누락·불일치 */
        UNAUTHORIZED,
        /** 502/503/504·연결 실패: 재시도 후에도 실패 */
        UNAVAILABLE,
        /** 기다리는 시간 초과 */
        TIMEOUT,
        /** 200이지만 internal_audit.gate_violations가 있음 */
        GATE_BLOCKED,
        /** 500 또는 응답 형식 이상 */
        INTERNAL,
        /** URL·키가 설정되지 않음 */
        NOT_CONFIGURED
    }

    private final Code code;
    private final int status;

    public AiException(Code code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public AiException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = 0;
    }

    public Code code() {
        return code;
    }

    /** 모델 서버의 HTTP 상태. 응답을 받지 못했으면 0. */
    public int status() {
        return status;
    }
}
