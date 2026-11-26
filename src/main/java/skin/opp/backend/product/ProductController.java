package skin.opp.backend.product;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.product.ProductDtos.Category;
import skin.opp.backend.product.ProductDtos.MyProductKind;
import skin.opp.backend.product.ProductDtos.MyProductRequest;
import skin.opp.backend.product.ProductDtos.MyProductView;
import skin.opp.backend.product.ProductDtos.PriceRange;
import skin.opp.backend.product.ProductDtos.ProductDetail;
import skin.opp.backend.product.ProductDtos.ProductPage;
import skin.opp.backend.product.ProductDtos.Recommendations;
import skin.opp.backend.product.ProductDtos.SkinFit;
import skin.opp.backend.product.ProductDtos.Sort;

@RestController
public class ProductController {

    private final ProductService service;
    private final RecommendationService recommendations;

    public ProductController(ProductService service, RecommendationService recommendations) {
        this.service = service;
        this.recommendations = recommendations;
    }

    /** 맞춤 추천: 피부 유형 + 상담 리포트(consultationId, 없으면 최근 리포트) + 주의 성분으로 궁합 점수순 */
    @GetMapping("/api/products/recommendations")
    public Recommendations recommendations(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestParam(required = false) Long consultationId,
            @RequestParam(defaultValue = "10") int limit) {
        return recommendations.recommend(userId(principal), consultationId, limit);
    }

    @GetMapping("/api/products")
    public ProductPage list(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestParam(defaultValue = "") List<Category> category,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") List<SkinFit> skin,
            @RequestParam(defaultValue = "") List<PriceRange> price,
            @RequestParam(defaultValue = "RECOMMENDED") Sort sort,
            @RequestParam(defaultValue = "0") int page) {
        return service.list(userId(principal), category, q, skin, price, sort, page);
    }

    @GetMapping("/api/products/{id}")
    public ProductDetail detail(@AuthenticationPrincipal OAuth2User principal, @PathVariable long id) {
        return service.detail(userId(principal), id);
    }

    @GetMapping("/api/my-products")
    public List<MyProductView> myProducts(
            @AuthenticationPrincipal OAuth2User principal, @RequestParam(required = false) MyProductKind kind) {
        return service.myProducts(userId(principal), kind);
    }

    @PostMapping("/api/my-products")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@AuthenticationPrincipal OAuth2User principal, @Valid @RequestBody MyProductRequest request) {
        service.addMyProduct(userId(principal), request.productId(), request.kind());
    }

    @DeleteMapping("/api/my-products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal OAuth2User principal, @PathVariable long productId,
                       @RequestParam MyProductKind kind) {
        service.removeMyProduct(userId(principal), productId, kind);
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
