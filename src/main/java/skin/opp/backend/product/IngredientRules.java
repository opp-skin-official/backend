package skin.opp.backend.product;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 성분 규칙 시트(docs/rules/opp_ingredient_rules.csv → resources/rules/ingredient_rules.csv).
 * 점수에는 INCI가 있는 "성분" 규칙만 쓴다. "그룹"(예: 에센셜 오일류)은 그룹 정의가 없어 아직 쓰지 않고,
 * "사용법"(예: 낮 시간 레티놀)은 제품이 아니라 사용 안내라 쓰지 않는다.
 */
@Component
public class IngredientRules {

    public enum Kind { HELP, CAUTION }

    /** letter: O/D/S/R/P/N/W/T, inci는 MatchScorer.normalize로 맞춘 값 */
    public record Rule(char letter, Kind kind, String ingredientKo, String inci, String reason) {}

    private final List<Rule> rules;

    public IngredientRules() {
        this(load("rules/ingredient_rules.csv"));
    }

    IngredientRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Rule> all() {
        return rules;
    }

    static List<Rule> load(String path) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            List<Rule> out = new ArrayList<>();
            String header = in.readLine(); // letter,kind,ingredient_ko,inci,match_type,priority,reason
            if (header == null) return out;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",", 7);
                if (c.length < 7 || !"성분".equals(c[4].strip()) || c[3].isBlank()) continue;
                Kind kind = switch (c[1].strip()) {
                    case "도움" -> Kind.HELP;
                    case "주의" -> Kind.CAUTION;
                    default -> null;
                };
                if (kind == null) continue;
                out.add(new Rule(c[0].strip().charAt(0), kind, c[2].strip(), MatchScorer.normalize(c[3]), c[6].strip()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("성분 규칙을 읽지 못했어요: " + path, e);
        }
    }
}
