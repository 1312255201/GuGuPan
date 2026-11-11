package cn.gugupan;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    HttpSessionEventPublisher sessionEvents() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    UserDetailsService userDetailsService(PanService service) {
        return username -> {
            if (!service.settings().username().equals(username)) {
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException("用户不存在");
            }
            return User.withUsername(username).password(service.adminPasswordHash()).roles("ADMIN").build();
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, RequestLimiter limiter, SessionRegistry sessions) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> json(response, 200, "登录成功"))
                        .failureHandler((request, response, exception) -> json(response, 401, "账号或密码错误")))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> json(response, 200, "已退出登录")))
                .sessionManagement(session -> session.maximumSessions(10).sessionRegistry(sessions)
                        .expiredSessionStrategy(event -> json(event.getResponse(), 401, "登录已失效，请重新登录")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> json(response, 401, "请先登录"))
                        .accessDeniedHandler((request, response, exception) -> json(response, 403, "访问受限或会话已过期，请刷新页面")))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                      FilterChain chain) throws ServletException, IOException {
                        try {
                            if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(request.getServletPath())) {
                                limiter.check("login:" + request.getRemoteAddr(), 30);
                            }
                            chain.doFilter(request, response);
                        } catch (ApiException exception) {
                            json(response, exception.status(), exception.getMessage());
                        }
                    }
                }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void json(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
