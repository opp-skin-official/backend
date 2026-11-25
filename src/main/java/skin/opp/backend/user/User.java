package skin.opp.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kakao_id", nullable = false, unique = true)
    private Long kakaoId;

    private String nickname;

    private String email;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {}

    public static User fromKakao(KakaoProfile profile) {
        User user = new User();
        user.kakaoId = profile.kakaoId();
        user.createdAt = Instant.now();
        user.applyProfile(profile);
        return user;
    }

    /** 로그인할 때마다 카카오 프로필(닉네임, 사진)을 최신으로 맞춘다. */
    public void applyProfile(KakaoProfile profile) {
        this.nickname = profile.nickname();
        this.profileImageUrl = profile.profileImageUrl();
        if (profile.email() != null) {
            this.email = profile.email();
        }
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getKakaoId() { return kakaoId; }
    public String getNickname() { return nickname; }
    public String getEmail() { return email; }
    public String getProfileImageUrl() { return profileImageUrl; }
    public Instant getDeletedAt() { return deletedAt; }
}
