package skin.opp.backend.review;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import skin.opp.backend.auth.KakaoOAuth2UserService;
import skin.opp.backend.review.ReviewDtos.ReviewForm;
import skin.opp.backend.review.ReviewDtos.ReviewPage;
import skin.opp.backend.review.ReviewDtos.ReviewRequest;
import skin.opp.backend.review.ReviewDtos.ReviewView;

@RestController
@RequestMapping("/api/products/{productId}")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping("/reviews")
    public ReviewPage list(@AuthenticationPrincipal OAuth2User principal, @PathVariable long productId,
                           @RequestParam(defaultValue = "0") int page) {
        return service.list(userId(principal), productId, page);
    }

    @GetMapping("/review-form")
    public ReviewForm form(@AuthenticationPrincipal OAuth2User principal, @PathVariable long productId) {
        return service.form(userId(principal), productId);
    }

    @PutMapping("/reviews/me")
    public ReviewView save(@AuthenticationPrincipal OAuth2User principal, @PathVariable long productId,
                           @Valid @RequestBody ReviewRequest request) {
        return service.save(userId(principal), productId, request);
    }

    @DeleteMapping("/reviews/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal OAuth2User principal, @PathVariable long productId) {
        service.delete(userId(principal), productId);
    }

    private static long userId(OAuth2User principal) {
        return ((Number) principal.getAttribute(KakaoOAuth2UserService.USER_ID)).longValue();
    }
}
