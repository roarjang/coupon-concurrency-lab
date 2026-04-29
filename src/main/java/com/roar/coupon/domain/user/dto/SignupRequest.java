package com.roar.coupon.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * record를 쓰면 좋은 이유
 * 첫째, DTO가 불변이 된다. (요청으로 들어온 값이나 응답으로 내보낼 값을 중간에 바꾸지 않게 된다)
 * 둘째, 코드가 짧아진다. (@Getter, 생성자, 필드 선언을 반복하지 않아도 된다)
 * 셋째, DTO의 의도가 명확하다. (이 객체는 로직을 가진 도메인 객체가 아니라, 값을 전달하는 객체다)
 */

/*
 * record는 기본적으로 아래를 자동 생성한다.
 * 생성자
 * getter 역할의 접근자 (request.email(), request.passowrd() ...)
 * equals()
 * hashCode()
 * toString()
 */

public record SignupRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 6, message = "비밀번호는 최소 6글자 이상을 입력해 주세요.")
        String password,

        @NotBlank(message = "사용자명은 필수입니다.")
        @Size(min = 2, max = 30, message = "사용자명은 2~30글자 범위 내에서 입력해 주세요.")
        String username
) {
}
