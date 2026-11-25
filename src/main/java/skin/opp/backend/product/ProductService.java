package skin.opp.backend.product;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import skin.opp.backend.product.ProductDtos.Category;
import skin.opp.backend.product.ProductDtos.Ingredient;
import skin.opp.backend.product.ProductDtos.MyProductKind;
import skin.opp.backend.product.ProductDtos.MyProductView;
import skin.opp.backend.product.ProductDtos.PriceRange;
import skin.opp.backend.product.ProductDtos.ProductDetail;
import skin.opp.backend.product.ProductDtos.ProductPage;
import skin.opp.backend.product.ProductDtos.ProductSummary;
import skin.opp.backend.product.ProductDtos.SkinFit;
import skin.opp.backend.product.ProductDtos.Sort;

@Service
public class ProductService {

    static final int PAGE_SIZE = 20;
    static final int MAX_QUERY_LENGTH = 50;

    private static final String SUMMARY_COLUMNS = """
            p.id, p.brand, p.name, p.category, p.volume, p.price, p.image_url,
            EXISTS (SELECT 1 FROM user_products u WHERE u.product_id = p.id AND u.user_id = :u AND u.kind = 'INTEREST') AS interested,
            EXISTS (SELECT 1 FROM user_products u WHERE u.product_id = p.id AND u.user_id = :u AND u.kind = 'OWNED') AS owned
            """;

    private final JdbcClient jdbc;

    public ProductService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ProductPage list(long userId, List<Category> categories, String query, List<SkinFit> skinFits,
                            List<PriceRange> prices, Sort sort, int page) {
        if (page < 0) {
            throw badRequest("페이지 번호가 올바르지 않아요.");
        }
        StringBuilder where = new StringBuilder("p.active");
        List<Object[]> params = new ArrayList<>();
        if (!categories.isEmpty()) {
            where.append(" AND p.category IN (:categories)");
            params.add(new Object[] {"categories", categories.stream().map(Enum::name).toList()});
        }
        String q = query == null ? "" : query.strip();
        if (q.length() > MAX_QUERY_LENGTH) {
            throw badRequest("검색어는 " + MAX_QUERY_LENGTH + "자까지 입력할 수 있어요.");
        }
        if (!q.isEmpty()) {
            where.append(" AND (p.name ILIKE :q ESCAPE '\\' OR p.brand ILIKE :q ESCAPE '\\')");
            params.add(new Object[] {"q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"});
        }
        if (!skinFits.isEmpty()) {
            where.append(" AND p.skin_fits && CAST(:skins AS text[])");
            params.add(new Object[] {"skins", skinFits.stream().map(Enum::name).toArray(String[]::new)});
        }
        if (!prices.isEmpty()) {
            List<String> ors = new ArrayList<>();
            for (int i = 0; i < prices.size(); i++) {
                PriceRange r = prices.get(i);
                ors.add(r.maxExclusive == null
                        ? "p.price >= :pmin" + i
                        : "(p.price >= :pmin" + i + " AND p.price < :pmax" + i + ")");
                params.add(new Object[] {"pmin" + i, r.min});
                if (r.maxExclusive != null) {
                    params.add(new Object[] {"pmax" + i, r.maxExclusive});
                }
            }
            where.append(" AND (").append(String.join(" OR ", ors)).append(")");
        }
        String order = switch (sort) {
            case PRICE_ASC -> "p.price ASC, p.id ASC";
            case PRICE_DESC -> "p.price DESC, p.id ASC";
            case RECOMMENDED -> "p.created_at DESC, p.id DESC";
        };
        var spec = jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM products p WHERE " + where
                        + " ORDER BY " + order + " LIMIT :limit OFFSET :offset")
                .param("u", userId).param("limit", PAGE_SIZE + 1).param("offset", page * PAGE_SIZE);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        List<ProductSummary> rows = spec.query((rs, n) -> summary(rs)).list();
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new ProductPage(hasNext ? rows.subList(0, PAGE_SIZE) : rows, page, hasNext);
    }

    public ProductDetail detail(long userId, long productId) {
        return jdbc.sql("""
                SELECT p.*, 
                  EXISTS (SELECT 1 FROM user_products u WHERE u.product_id = p.id AND u.user_id = :u AND u.kind = 'INTEREST') AS interested,
                  EXISTS (SELECT 1 FROM user_products u WHERE u.product_id = p.id AND u.user_id = :u AND u.kind = 'OWNED') AS owned
                FROM products p WHERE p.id = :id AND p.active
                """).param("u", userId).param("id", productId)
                .query((rs, n) -> new ProductDetail(
                        rs.getLong("id"), rs.getString("brand"), rs.getString("name"),
                        Category.valueOf(rs.getString("category")), rs.getString("volume"), rs.getInt("price"),
                        rs.getString("summary"), rs.getString("recommendation"),
                        strings(rs.getArray("skin_fits")).stream().map(SkinFit::valueOf).toList(),
                        strings(rs.getArray("features")),
                        ingredients(productId), rs.getString("full_ingredients"), rs.getString("image_url"),
                        rs.getBoolean("interested"), rs.getBoolean("owned")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제품을 찾을 수 없어요."));
    }

    public List<MyProductView> myProducts(long userId, MyProductKind kind) {
        String filter = kind == null ? "" : " AND m.kind = :kind";
        var spec = jdbc.sql("SELECT m.kind, " + SUMMARY_COLUMNS
                + " FROM user_products m JOIN products p ON p.id = m.product_id"
                + " WHERE m.user_id = :u AND p.active" + filter + " ORDER BY m.created_at DESC, m.id DESC")
                .param("u", userId);
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        return spec.query((rs, n) -> {
            ProductSummary p = summary(rs);
            return new MyProductView(p.id(), MyProductKind.valueOf(rs.getString("kind")), p);
        }).list();
    }

    @Transactional
    public void addMyProduct(long userId, long productId, MyProductKind kind) {
        boolean exists = jdbc.sql("SELECT count(*) FROM products WHERE id = :id AND active")
                .param("id", productId).query(Long.class).single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "제품을 찾을 수 없어요.");
        }
        jdbc.sql("INSERT INTO user_products (user_id, product_id, kind) VALUES (:u, :p, :k) ON CONFLICT DO NOTHING")
                .param("u", userId).param("p", productId).param("k", kind.name()).update();
    }

    @Transactional
    public void removeMyProduct(long userId, long productId, MyProductKind kind) {
        jdbc.sql("DELETE FROM user_products WHERE user_id = :u AND product_id = :p AND kind = :k")
                .param("u", userId).param("p", productId).param("k", kind.name()).update();
    }

    private List<Ingredient> ingredients(long productId) {
        return jdbc.sql("SELECT name_ko, inci FROM product_ingredients WHERE product_id = :id ORDER BY sort_order")
                .param("id", productId)
                .query((rs, n) -> new Ingredient(rs.getString("name_ko"), rs.getString("inci"))).list();
    }

    private static ProductSummary summary(ResultSet rs) throws SQLException {
        return new ProductSummary(rs.getLong("id"), rs.getString("brand"), rs.getString("name"),
                Category.valueOf(rs.getString("category")), rs.getString("volume"), rs.getInt("price"),
                rs.getString("image_url"), rs.getBoolean("interested"), rs.getBoolean("owned"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
