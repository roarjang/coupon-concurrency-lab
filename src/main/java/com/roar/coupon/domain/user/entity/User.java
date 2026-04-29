package com.roar.coupon.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/*
 * User는 쿠폰 발급, 포인트 보유, 주문 생성의 기준이 되는 주체입니다.
 * 회원 기능 자체가 핵심은 아니기 때문에 이메일 기반 로그인에 필요한 최소 필드만 두었습니다.
 * 회원가입 시 애플리케이션 레벨에서 이메일 중복 검사를 수행하고,
 * 동시에 들어오는 중복 가입 요청까지 방지하기 위해 DB의 unique 제약조건을 함께 사용했습니다.
 * 권한은 일반 사용자와 테스트용 관리자 확장을 고려해 enum으로 분리했습니다.
 * Entity에는 Setter를 열지 않고 생성 메서드를 통해 일관된 초기 상태를 만들도록 설계했습니다.
 */

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable =false)
    private String password;

    @Column(nullable = false, length = 30)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static User create(String email, String encodedPassword, String userName) {
        User user = new User();
        user.email = email;
        user.password = encodedPassword;
        user.userName = userName;
        user.role = UserRole.USER;

        return user;
    }

    @PrePersist
    public void beforePersist() {
        // 엔티티가 저장되기 전에 호출되는 메서드
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void beforeUpdate() {
        // 엔티티가 업데이트되기 전에 호출되는 메서드
        this.updatedAt = LocalDateTime.now();
    }
}
