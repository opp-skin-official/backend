package skin.opp.backend.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 피부 Q&A 가드레일 (규칙 기반). AI 서버의 금칙어 검사와 별개로 우리 쪽에서 한 번 더 막는다.
 * <ul>
 *   <li>입력: 프롬프트 인젝션·탈옥 시도, 위급 신호(자해 등)는 AI를 부르지 않고 정해진 안내로 답한다.</li>
 *   <li>개인정보: 주민번호·전화번호·이메일·군번·계좌번호는 가린 뒤 AI(해외 서버)에 보낸다.</li>
 *   <li>출력: 진단 단정, 전문의약품 권유, 지시문 노출이 있으면 답변을 안내 문구로 바꾼다.</li>
 * </ul>
 * 규칙은 정상 피부 질문을 막지 않도록 좁게 잡는다(ChatGuardTest의 정상 질문 목록으로 확인).
 */
@Component
public class ChatGuard {

    /** 입력 검사 결과. OK가 아니면 AI를 부르지 않는다. */
    public enum Input { OK, INJECTION, CRISIS }

    static final String INJECTION_REPLY =
            "저는 피부 관리에 관한 질문에만 답할 수 있어요. 피부 고민이나 관리 방법을 편하게 물어봐 주세요.";
    static final String CRISIS_REPLY = """
            많이 힘드신 것 같아요. 혼자 견디지 말고 지금 바로 도움을 받아 주세요.
            - 자살예방 상담전화 **109** (24시간)
            - 국방헬프콜 **1303** (24시간, 군 생활 고민 상담)
            - 부대 병영생활전문상담관이나 믿을 수 있는 간부·동료에게 이야기해 주세요.
            - 위급하면 **119**에 연락해 주세요.""";

    // 공백을 모두 뺀 소문자 문장에 맞춘다("이전 지시를  무시해" → "이전지시를무시해").
    private static final List<Pattern> INJECTION = compile(
            "(이전|앞|위|기존|모든)의?(지시|명령|지침|프롬프트|설정)(사항)?(을|를|은|는)?(다|전부|모두)?(무시|잊어|잊고|지워)",
            "(지시|명령|지침)(사항)?(을|를)?(다|전부|모두)?무시",
            "시스템(프롬프트|메시지|지시|지침)",
            "(너의|네|당신의)(지시|지침|설정|규칙|프롬프트)(을|를)?.{0,4}(보여|알려|출력|말해)",
            "프롬프트(를|을)?.{0,4}(보여|알려|출력|공개)",
            "개발자모드", "탈옥", "제한(을|를)?(해제|풀어|없애)", "검열(을|를)?(해제|풀어|없애|하지마)",
            "(지금부터|이제부터|이제)(너는|넌|당신은)", "역할극",
            "ignore(all|any|the)?(previous|prior|above)?(instructions|prompts|rules)",
            "disregard(all|the)?(previous|prior|above)", "systemprompt", "developermode", "jailbreak",
            "youarenow", "actas(a|an)", "pretendtobe", "doanythingnow");

    private static final List<Pattern> CRISIS = compile(
            "자살", "자해", "죽고싶", "죽어버리고싶", "살기싫", "사라지고싶", "목숨(을)?끊",
            "손목(을)?긋", "극단적(인)?선택", "killmyself", "suicid");

    /** 진단 단정·처방·전문의약품 권유·지시문 노출 (원문 그대로에 맞춘다) */
    private static final List<Named> OUTPUT = List.of(
            new Named("diagnosis", Pattern.compile("(으로|로)\\s*진단(합니다|됩니다|돼요|할 수 있습니다)|진단(을)?\\s*(내리|드리)")),
            new Named("prescription", Pattern.compile("처방(해\\s*드|합니다|해요|하겠)")),
            // 전문의약품은 이름만으로 막지 않고(“복용 중이면 상담하세요” 같은 주의 안내는 괜찮다) 쓰라고 권할 때만 막는다.
            new Named("rx_drug", Pattern.compile(
                    "(이소트레티노인|isotretinoin|로아큐탄|아큐탄|독시사이클린|doxycycline|미노사이클린|minocycline"
                            + "|트레티노인|tretinoin|클린다마이신|clindamycin|하이드로퀴논|hydroquinone|스테로이드\\s*연고)"
                            + "[^.!?\\n]{0,20}(바르세요|발라\\s*보세요|드세요|복용하세요|드셔\\s*보세요|사용하세요|써\\s*보세요|추천(해요|합니다|드려요|드립니다))",
                    Pattern.CASE_INSENSITIVE)),
            new Named("prompt_leak", Pattern.compile(
                    "시스템\\s*프롬프트|system\\s*prompt|<\\|im_start\\|>|\\[INST\\]|<<SYS>>", Pattern.CASE_INSENSITIVE)));

    private record Named(String name, Pattern pattern) {}

    // 개인정보: 순서대로 바꾼다(주민번호를 전화번호보다 먼저)
    private static final Pattern RRN = Pattern.compile("(?<!\\d)\\d{6}\\s?-\\s?[1-8]\\d{6}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)01[016789][\\s.-]?\\d{3,4}[\\s.-]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern SERVICE_NUMBER = Pattern.compile("(?<!\\d)\\d{2}-\\d{8}(?!\\d)");
    private static final Pattern ACCOUNT = Pattern.compile("(?<!\\d)\\d{2,6}-\\d{2,6}-\\d{2,7}(-\\d{1,3})?(?!\\d)");

    public Input checkInput(String text) {
        String compact = compact(text);
        if (matches(CRISIS, compact)) return Input.CRISIS;
        if (matches(INJECTION, compact)) return Input.INJECTION;
        return Input.OK;
    }

    /** 입력이 막혔을 때 보여 줄 답 */
    public String reply(Input input) {
        return switch (input) {
            case INJECTION -> INJECTION_REPLY;
            case CRISIS -> CRISIS_REPLY;
            case OK -> throw new IllegalArgumentException("막히지 않은 입력");
        };
    }

    /** AI에 보내기 전에 개인정보를 가린다. */
    public String maskPersonalInfo(String text) {
        String out = RRN.matcher(text).replaceAll("[주민등록번호]");
        out = PHONE.matcher(out).replaceAll("[전화번호]");
        out = EMAIL.matcher(out).replaceAll("[이메일]");
        out = SERVICE_NUMBER.matcher(out).replaceAll("[군번]");
        Matcher m = ACCOUNT.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // 날짜(2026-11-27)처럼 짧은 숫자는 두고, 10자리 이상만 계좌번호로 본다.
            long digits = m.group().chars().filter(Character::isDigit).count();
            m.appendReplacement(sb, digits >= 10 ? "[계좌번호]" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 답변에서 걸린 규칙 이름들 (없으면 빈 목록) */
    public List<String> checkOutput(String answer) {
        List<String> hits = new ArrayList<>();
        if (answer == null) return hits;
        for (Named rule : OUTPUT) if (rule.pattern().matcher(answer).find()) hits.add(rule.name());
        return hits;
    }

    private static String compact(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…‘’“”]", "");
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) if (p.matcher(text).find()) return true;
        return false;
    }

    private static List<Pattern> compile(String... regexes) {
        List<Pattern> out = new ArrayList<>();
        for (String r : regexes) out.add(Pattern.compile(r));
        return out;
    }
}
