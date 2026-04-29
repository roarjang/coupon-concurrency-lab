package com.roar.coupon.auth.jwt;

import io.jsonwebtoken.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // OncePerRequestFilter: 하나의 HTTP 요청당 딱 한 번만 실행되는 필터

    private final JwtProvider jwtProvider;

    @Override
    public void doFilterInternal(
            HttpServletRequest request,     // 클라이언트의 HTTP 요청
            HttpServletResponse response,   // 서버의 HTTP 응답
            FilterChain filterChain         // 다음 필터로 요청을 넘기는 체인
    ) throws ServletException, IOException {

        // 1. Authorization 헤더에서 토큰 추출
        String authorizationHeader = request.getHeader("Authorization");

        // 헤더가 없거나 "Bearer "로 시작하지 않으면 -> JWT 인증 대상이 아님
        // 다음 필터로 넘기고 현재 필터 종료 (비로그인 요청 허용)
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. "Bearer " 접두사 제거 후 순수 토큰 값 추출
        String token = authorizationHeader.substring(7);

        // 3. 토큰 검증 및 Spring Security 인증 컨텍스트 등록
        Claims claims;

        // 토큰이 유효하지 않은 경우: SecurityContext에 인증 정보를 저장하지 않음
        // -> Spring Security가 인증되지 않은 요청으로 처리 (403/401 응답)
        try {
            claims = jwtProvider.parseClaims(token);
        } catch (ExpiredJwtException e) {
            log.warn("만료된 토큰: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        } catch (UnsupportedJwtException | MalformedJwtException e) {
            log.warn("잘못된 토큰 형식: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        } catch (JwtException e) {
            log.warn("유효하지 않은 토큰: {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "INVALID JWT");
            return;
        } catch (IllegalArgumentException e) {
            log.warn("토큰이 비어있음: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // 토큰 payload에서 사용자 정보 추출
        Long userId = jwtProvider.getUserId(claims);
        String role = jwtProvider.getRole(claims);

        // Spring Security가 요구하는 권한 목록 생성
        // "ROLE_" 접두사는 Spring Security 규칙 (예: ROLE_USER, ROLE_ADMIN)
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + role));

        // 인증 객체 생성
        // 인자: principal(식별자), credentials(비밀번호), authorities(권한목록)
        // JWT 방식이므로 credentials는 null (이미 토큰으로 검증 완료)
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, authorities);

        // SecurityContext에 인증 정보 저장
        // -> 이후 컨트롤러에서 @AuthenticationPrincipal 등으로 사용자 정보 접근 가능
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 다음 필터로 요청 전달 (필터 체인 계속 진행)
        filterChain.doFilter(request, response);
    }
}
