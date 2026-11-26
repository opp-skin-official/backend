package skin.opp.backend;

import skin.opp.backend.user.KakaoProfile;
import skin.opp.backend.user.TermsService;
import skin.opp.backend.user.UserService;

/** 통합 테스트용 사용자: 가입하고 필수 약관에 동의한 상태(실제 사용자가 앱을 쓰는 상태)로 만든다. */
public final class TestUsers {

    private TestUsers() {}

    public static long member(UserService users, TermsService terms, String nickname) {
        long id = users.upsertFromKakao(new KakaoProfile(System.nanoTime(), nickname, null, null)).getId();
        terms.agree(id, TermsService.CURRENT_VERSION);
        return id;
    }
}
