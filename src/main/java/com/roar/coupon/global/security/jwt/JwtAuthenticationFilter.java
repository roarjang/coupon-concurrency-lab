package com.roar.coupon.global.security.jwt;

import com.roar.coupon.global.security.CustomUserDetails;
import com.roar.coupon.global.security.CustomUserDetailsService;
import io.jsonwebtoken.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // OncePerRequestFilter: 하나의 HTTP 요청당 딱 한 번만 실행되는 필터

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    public void doFilterInternal(
            HttpServletRequest request,     // 클라이언트의 HTTP 요청
            HttpServletResponse response,   // 서버의 HTTP 응답
            FilterChain filterChain         // 다음 필터로 요청을 넘기는 체인
    ) throws ServletException, IOException {

        // 1. 토큰 추출
        String token = resolveToken(request);

        // 토큰이 유효하지 않은 경우: SecurityContext에 인증 정보를 저장하지 않음
        // -> Spring Security가 인증되지 않은 요청으로 처리 (403/401 응답)
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Claims claims;

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

        // 2. payload에서 userId 추출
        Long userId = jwtProvider.getUserId(claims);

        // CustomUserDetails 생성
        CustomUserDetails userDetails =
                customUserDetailsService.loadUserById(userId);

        // 3. 인증 객체 생성
        // 인자: principal(식별자), credentials(비밀번호), authorities(권한목록)
        // JWT 방식이므로 credentials는 null (이미 토큰으로 검증 완료)
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

        // 4. SecurityContext에 인증 정보 저장
        // -> 이후 컨트롤러에서 @AuthenticationPrincipal 등으로 사용자 정보 접근 가능
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 다음 필터로 요청 전달 (필터 체인 계속 진행)
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }

        return authorization.substring(7);
    }
}
