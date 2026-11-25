package skin.opp.backend.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    /** 처음 로그인하면 사용자를 만들고, 이미 있으면 프로필을 갱신한다. */
    @Transactional
    public User upsertFromKakao(KakaoProfile profile) {
        return users.findByKakaoId(profile.kakaoId())
                .map(user -> {
                    user.applyProfile(profile);
                    return user;
                })
                .orElseGet(() -> users.save(User.fromKakao(profile)));
    }
}
