package com.attendance.audit.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final SecurityConfig.LoginRateLimiter rateLimiter;

    public LoginRateLimitFilter(SecurityConfig.LoginRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("/login".equals(request.getRequestURI()) && "POST".equalsIgnoreCase(request.getMethod())) {
            String ip = request.getRemoteAddr();
            if (rateLimiter.isBlocked(ip)) {
                response.setStatus(429);
                response.setContentType("text/html;charset=UTF-8");
                response.getWriter().write("<html><body><h2>登录尝试过多</h2><p>请 5 分钟后再试。</p></body></html>");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
