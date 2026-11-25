package skin.opp.backend.review;

import java.util.List;
import skin.opp.backend.product.ProductDtos.Category;

/**
 * 좋았던 점·아쉬운 점 태그. 문구는 Figma 그대로.
 * 클렌저는 "리뷰작성", 그 밖의 제품은 "보유제품 등록" 화면의 태그를 쓴다.
 */
final class ReviewTags {

    record Options(List<String> good, List<String> bad) {}

    static final Options CLEANSER = new Options(
            List.of("세정력이 좋아요", "순해요", "산뜻해요", "거품이 잘나요"),
            List.of("건조해요", "향이 강해요", "자극이 있어요"));

    static final Options SKINCARE = new Options(
            List.of("보습력이 좋아요", "피부자극이 적어요", "흡수가 빨라요", "사용감이 산뜻해요"),
            List.of("끈적임이 있어요", "피부가 건조해요", "자극이 느껴져요", "향이 강해요"));

    private ReviewTags() {}

    static Options of(Category category) {
        return category == Category.CLEANSER ? CLEANSER : SKINCARE;
    }
}
