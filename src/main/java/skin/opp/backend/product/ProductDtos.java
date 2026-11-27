package skin.opp.backend.product;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class ProductDtos {

    private ProductDtos() {}

    /** 제품 유형 (Figma 필터: 클렌저·토너·로션·크림·선크림 + 에센스/앰플, 마스크팩=AI 루틴 step_type mask_pack) */
    public enum Category { CLEANSER, TONER, LOTION, ESSENCE, CREAM, SUNSCREEN, MASK_PACK }

    /** 필터 "피부 특성" */
    public enum SkinFit { OILY, DRY, COMBINATION, SENSITIVE }

    /** 필터 "가격대" */
    public enum PriceRange {
        UNDER_10K(0, 10_000), FROM_10K_TO_20K(10_000, 20_000), FROM_20K_TO_30K(20_000, 30_000), OVER_30K(30_000, null);

        final int min;
        final Integer maxExclusive;

        PriceRange(int min, Integer maxExclusive) {
            this.min = min;
            this.maxExclusive = maxExclusive;
        }
    }

    /** 정렬. 추천순은 매칭(성분 그룹 정의) 전까지 등록순으로 동작한다. */
    public enum Sort { RECOMMENDED, PRICE_ASC, PRICE_DESC }

    /** 내 보유제품 종류: 구매한 제품 / 관심 제품 */
    public enum MyProductKind { OWNED, INTEREST }

    /** matchScore = 궁합 점수 0~99 (피부 유형이 없거나 점수를 내지 않은 목록이면 null) */
    public record ProductSummary(
            long id, String brand, String name, Category category, String volume, int price,
            String imageUrl, boolean interested, boolean owned, Integer matchScore) {

        public ProductSummary withMatchScore(Integer score) {
            return new ProductSummary(id, brand, name, category, volume, price, imageUrl, interested, owned, score);
        }
    }

    public record ProductPage(List<ProductSummary> items, int page, boolean hasNext) {}

    public record Ingredient(String nameKo, String inci) {}

    public record ProductDetail(
            long id, String brand, String name, Category category, String volume, int price,
            String summary, String recommendation, List<SkinFit> skinFits, List<String> features,
            List<Ingredient> keyIngredients, String fullIngredients, String imageUrl,
            boolean interested, boolean owned) {}

    /** 맞춤 추천 하나: score는 0~99(피부 유형이 없으면 null), reasons = 좋은 점, cautions = 주의할 점 */
    public record Recommendation(ProductSummary product, Integer score, List<String> reasons, List<String> cautions) {}

    /** hasSkinType = 설문 결과가 있어 점수를 냈는지, usedReport = 상담 리포트 성분을 반영했는지 */
    public record Recommendations(boolean hasSkinType, boolean usedReport, List<Recommendation> items) {}

    public record MyProductRequest(@NotNull Long productId, @NotNull MyProductKind kind) {}

    public record MyProductView(long productId, MyProductKind kind, ProductSummary product) {}
}
