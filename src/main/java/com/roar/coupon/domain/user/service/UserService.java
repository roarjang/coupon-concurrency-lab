package com.roar.coupon.domain.user.service;

import com.roar.coupon.domain.point.entity.Point;
import com.roar.coupon.domain.point.repoistory.PointRepository;
import com.roar.coupon.domain.user.dto.SignupRequest;
import com.roar.coupon.domain.user.dto.UserResponse;
import com.roar.coupon.domain.user.entity.User;
import com.roar.coupon.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PointRepository pointRepository;

    /*
     * 트랜잭션 포인트 (애플리케이션 로직 + DB 제약)
     * User 저장 성공 + Point 저장 성공 -> commit
     * 둘 중 하나라도 실패 -> rollback
     * DB에서 unique constraint로 차단
     */
    @Transactional
    public UserResponse signup(SignupRequest request) {
        // 1. 이메일 중복 조회
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        // 2. 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(request.password());

        // 3. User 생성
        User user = User.create(request.email(), encodedPassword, request.username());

        // 4. 저장
        // IDENTITY 전략에서는 저장 시점에 id가 할당되므로,
        // 응답에 id를 넣으려면 저장 이후 객체를 사용하는 게 자연스럽다.
        User savedUser = userRepository.save(user);

        // 5. Point 생성
        pointRepository.save(new Point(savedUser.getId()));

        // 6. 응답 반환
        return UserResponse.from(savedUser);
    }
}
