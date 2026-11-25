package skin.opp.backend.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ReviewDtos {

    private ReviewDtos() {}

    /** 구매 옵션 (Figma "리뷰작성"): 지성용·건성용·수부지용·민감성용·복합성용 */
    public enum SkinOption { OILY, DRY, OILY_DRY, SENSITIVE, COMBINATION }

    public record ReviewRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            @NotBlank @Size(max = 500) String content,
            SkinOption skinOption,
            @NotNull List<String> goodTags,
            @NotNull List<String> badTags) {}

    public record ReviewView(
            long id, String nickname, int rating, String content, SkinOption skinOption,
            List<String> goodTags, List<String> badTags, Instant createdAt, boolean mine) {}

    public record ReviewPage(double average, int count, List<ReviewView> items, int page, boolean hasNext) {}

    /** 리뷰 작성 화면: 제품 유형에 맞는 태그 선택지와 내가 쓴 리뷰(없으면 null) */
    public record ReviewForm(List<String> goodTagOptions, List<String> badTagOptions, ReviewView myReview) {}
}
