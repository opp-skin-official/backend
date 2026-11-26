package skin.opp.backend.review;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.product.ProductDtos.Category;
import skin.opp.backend.review.ReviewDtos.ReviewForm;
import skin.opp.backend.review.ReviewDtos.ReviewPage;
import skin.opp.backend.review.ReviewDtos.ReviewRequest;
import skin.opp.backend.review.ReviewDtos.ReviewView;
import skin.opp.backend.review.ReviewDtos.SkinOption;

@Service
public class ReviewService {

    static final int PAGE_SIZE = 10;
    /** 이보다 큰 페이지 번호는 거절한다(오프셋 곱셈이 int를 넘지 않게) */
    static final int MAX_PAGE = 1000;

    private static final String VIEW_COLUMNS = """
            r.id, u.nickname, r.rating, r.content, r.skin_option, r.good_tags, r.bad_tags, r.created_at,
            (r.user_id = :me) AS mine
            """;

    private final JdbcClient jdbc;

    public ReviewService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ReviewPage list(long userId, long productId, int page) {
        category(productId); // 없는 제품이면 404
        if (page < 0 || page > MAX_PAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 번호가 올바르지 않아요.");
        }
        var stats = jdbc.sql("SELECT count(*) AS n, coalesce(avg(rating), 0) AS avg FROM product_reviews WHERE product_id = :p")
                .param("p", productId)
                .query((rs, n) -> new double[] {rs.getLong("n"), rs.getDouble("avg")}).single();
        List<ReviewView> rows = jdbc.sql("SELECT " + VIEW_COLUMNS + """
                 FROM product_reviews r JOIN users u ON u.id = r.user_id
                WHERE r.product_id = :p ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset
                """).param("me", userId).param("p", productId)
                .param("limit", PAGE_SIZE + 1).param("offset", page * PAGE_SIZE)
                .query((rs, n) -> view(rs)).list();
        boolean hasNext = rows.size() > PAGE_SIZE;
        double average = Math.round(stats[1] * 10) / 10.0;
        return new ReviewPage(average, (int) stats[0], hasNext ? rows.subList(0, PAGE_SIZE) : rows, page, hasNext);
    }

    public ReviewForm form(long userId, long productId) {
        ReviewTags.Options options = ReviewTags.of(category(productId));
        return new ReviewForm(options.good(), options.bad(), myReview(userId, productId));
    }

    /** 사용자당 제품별 리뷰 1건. 다시 쓰면 고친다. */
    @Transactional
    public ReviewView save(long userId, long productId, ReviewRequest request) {
        ReviewTags.Options options = ReviewTags.of(category(productId));
        List<String> good = validTags(request.goodTags(), options.good(), "좋았던 점");
        List<String> bad = validTags(request.badTags(), options.bad(), "아쉬운 점");
        String content = request.content().strip();
        jdbc.sql("""
                INSERT INTO product_reviews (product_id, user_id, rating, content, skin_option, good_tags, bad_tags)
                VALUES (:p, :u, :rating, :content, :skin, :good, :bad)
                ON CONFLICT (product_id, user_id) DO UPDATE SET
                    rating = EXCLUDED.rating, content = EXCLUDED.content, skin_option = EXCLUDED.skin_option,
                    good_tags = EXCLUDED.good_tags, bad_tags = EXCLUDED.bad_tags, updated_at = now()
                """)
                .param("p", productId).param("u", userId).param("rating", request.rating())
                .param("content", content)
                .param("skin", request.skinOption() == null ? null : request.skinOption().name())
                .param("good", good.toArray(String[]::new)).param("bad", bad.toArray(String[]::new))
                .update();
        return myReview(userId, productId);
    }

    @Transactional
    public void delete(long userId, long productId) {
        int deleted = jdbc.sql("DELETE FROM product_reviews WHERE product_id = :p AND user_id = :u")
                .param("p", productId).param("u", userId).update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "작성한 리뷰가 없어요.");
        }
    }

    private ReviewView myReview(long userId, long productId) {
        return jdbc.sql("SELECT " + VIEW_COLUMNS
                        + " FROM product_reviews r JOIN users u ON u.id = r.user_id WHERE r.product_id = :p AND r.user_id = :me")
                .param("me", userId).param("p", productId)
                .query((rs, n) -> view(rs)).optional().orElse(null);
    }

    private Category category(long productId) {
        return jdbc.sql("SELECT category FROM products WHERE id = :id AND active").param("id", productId)
                .query(String.class).optional().map(Category::valueOf)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제품을 찾을 수 없어요."));
    }

    private static List<String> validTags(List<String> tags, List<String> allowed, String label) {
        List<String> unique = List.copyOf(new LinkedHashSet<>(tags));
        for (String tag : unique) {
            if (!allowed.contains(tag)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " 선택지가 올바르지 않아요: " + tag);
            }
        }
        return unique;
    }

    private static ReviewView view(java.sql.ResultSet rs) throws SQLException {
        String skin = rs.getString("skin_option");
        return new ReviewView(rs.getLong("id"), rs.getString("nickname"), rs.getInt("rating"), rs.getString("content"),
                skin == null ? null : SkinOption.valueOf(skin),
                strings(rs.getArray("good_tags")), strings(rs.getArray("bad_tags")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getBoolean("mine"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
