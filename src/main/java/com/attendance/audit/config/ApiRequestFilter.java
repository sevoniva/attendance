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

/**
 * API 请求防护：要求所有 /api/** 请求携带 X-Requested-With: XMLHttpRequest。
 * 该自定义 Header 无法被跨站简单请求设置，配合 SameSite=Strict Cookie 可有效防御 CSRF。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            String requestedWith = request.getHeader("X-Requested-With");
            if (!"XMLHttpRequest".equals(requestedWith)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Forbidden\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
