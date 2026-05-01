package com.roar.coupon.global.security;

import com.roar.coupon.domain.user.entity.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {

    private final Long userId;
    private final String email;
    private final String password;
    private final String role;

    /*
     * Spring Security는 기본적으로 ROLE_ prefix를 기준으로 권한을 처리한다.
     * 그래서 UserRole enum을 ROLE_USER 형태로 정의하거나,
     * CustomUserDetails에서 prefix를 붙여서 GrantedAuthority로 변환해야 한다.
     */
    public static CustomUserDetails from(User user) {
        return new CustomUserDetails(
                user.getId(),
                user.getEmail(),
                user.getPassword(),
                "ROLE_" + user.getRole().name()
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    /**
     * Spring Security에서 username은 반드시 실제 username일 필요는 없다.
     * 보통 로그인 식별자로 쓰는 email을 반환해도 된다.
     */
    @Override
    public String getUsername() {
        return email;
    }

    /**
     * 계정 만료 여부
     * true = 만료되지 않음
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * 계정 잠김 여부
     * true = 잠기지 않음
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * 비밀번호 만료 여부
     * true = 만료되지 않음
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 계정 활성화 여부
     * true = 활성화됨
     */
    @Override
    public boolean isEnabled() {
        return true;
    }
}
