package skin.opp.backend.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class ChatDtos {

    private ChatDtos() {}

    public enum Kind { QNA, CONSULT }

    public enum Role { USER, ASSISTANT }

    /** BLOCKED: AI 답변이 안전 검사에 걸려 안내 문구로 바꿔 저장함 */
    public enum Status { DONE, BLOCKED }

    /** useSavedProfile을 생략하면 true */
    public record ConversationRequest(Boolean useSavedProfile) {}

    public record MessageRequest(@NotBlank @Size(max = 1000) String content) {}

    public record ConversationSummary(long id, Kind kind, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /** 사용자 메시지는 content만 채운다. */
    public record MessageView(
            long id, Role role, Status status, String content,
            /* content 형식: markdown(v6.3 답변) 또는 text */
            String format,
            List<String> actionTips, List<String> followUpQuestions,
            boolean referToClinician, String notice, String referralNotice,
            OffsetDateTime createdAt) {}

    public record ConversationView(
            long id, Kind kind, String title, boolean useSavedProfile,
            OffsetDateTime createdAt, List<MessageView> messages) {}

    /** 스트리밍 중 글자 조각 (띄어쓰기가 빠질 수 있어 done의 content로 바꿔야 한다) */
    public record Delta(String text) {}

    public record StreamError(String code, String message) {}
}
