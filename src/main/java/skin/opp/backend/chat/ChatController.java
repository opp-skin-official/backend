package skin.opp.backend.chat;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import skin.opp.backend.ai.AiException;
import skin.opp.backend.ai.AiQuota;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.chat.ChatDtos.ConversationRequest;
import skin.opp.backend.chat.ChatDtos.ConversationSummary;
import skin.opp.backend.chat.ChatDtos.ConversationView;
import skin.opp.backend.chat.ChatDtos.Delta;
import skin.opp.backend.chat.ChatDtos.MessageRequest;
import skin.opp.backend.chat.ChatDtos.MessageView;
import skin.opp.backend.chat.ChatDtos.StreamError;

@RestController
@RequestMapping("/api/conversations")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** AI 응답 대기(최대 150초)보다 넉넉하게 */
    static final long SSE_TIMEOUT_MILLIS = 200_000;

    private final ChatService service;
    private final AiQuota quota;
    private final ChatGuard guard;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatService service, AiQuota quota, ChatGuard guard) {
        this.service = service;
        this.quota = quota;
        this.guard = guard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(@AuthenticationPrincipal OAuth2User principal,
                                   @RequestBody(required = false) ConversationRequest request) {
        boolean useSavedProfile = request == null || request.useSavedProfile() == null || request.useSavedProfile();
        return service.create(userId(principal), useSavedProfile);
    }

    @GetMapping
    public List<ConversationSummary> list(@AuthenticationPrincipal OAuth2User principal,
                                          @RequestParam(defaultValue = "20") int limit) {
        return service.list(userId(principal), limit);
    }

    @GetMapping("/{id}")
    public ConversationView get(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id) {
        return service.get(userId(principal), id);
    }

    /**
     * 질문을 보내고 답변을 SSE로 받는다.
     * 이벤트: question(저장된 질문) → delta(글자 조각, 여러 번) → done(저장된 답변) | error(code, message)
     */
    @PostMapping(path = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id,
                           @Valid @RequestBody MessageRequest request, HttpServletResponse response) {
        long userId = userId(principal);
        boolean useSavedProfile = service.requireOwned(userId, id);
        // 넘으면 429 (스트림을 열기 전). 가드레일에 걸려 AI를 부르지 않을 질문은 세지 않는다.
        if (guard.checkInput(request.content()) == ChatGuard.Input.OK) quota.acquire(userId, AiQuota.Kind.CHAT);
        MessageView question = service.saveUserMessage(id, request.content());

        // 앞단 nginx가 응답을 모았다가 한 번에 보내지 않게 한다(서버 설정과 상관없이 글자 조각이 바로 흐름).
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("question").data(question, MediaType.APPLICATION_JSON));
                MessageView answer = service.answer(userId, id, question.id(), useSavedProfile, text -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(new Delta(text), MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        // 사용자가 화면을 떠나도 답변은 끝까지 받아 저장한다.
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(answer, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (AiException e) {
                log.warn("conversation {} AI 실패: {} {} {}", id, e.code(), e.status(), e.getMessage());
                sendError(emitter, e.code().name(), userMessage(e));
            } catch (Exception e) {
                log.error("conversation {} 답변 처리 실패", id, e);
                sendError(emitter, "INTERNAL", "답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
            }
        });
        return emitter;
    }

    private static String userMessage(AiException e) {
        return switch (e.code()) {
            case TIMEOUT, UNAVAILABLE -> "AI 응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.";
            default -> "답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요.";
        };
    }

    private static void sendError(SseEmitter emitter, String code, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(new StreamError(code, message), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
