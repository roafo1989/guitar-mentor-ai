package ai.guitarmentor.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.web.csrf.CsrfToken;
import jakarta.servlet.http.HttpServletRequest;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ActorFilter actorFilter,
            @Value("${guitar-mentor.security.enabled:true}") boolean enabled) throws Exception {
        if (!enabled) {
            // Insecure fallback is reserved for existing localhost-only development.
            return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
        var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax")
                .secure(true));
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/error", "/login/**", "/oauth2/**", "/").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .oauth2Login(login -> login
                .successHandler((req, res, auth) -> {
                    res.sendRedirect("/");
                })
                .failureHandler((req, res, ex) -> res.sendRedirect("/?auth=failed")))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepo)
                .ignoringRequestMatchers(new AntPathRequestMatcher("/oauth2/**")))
            .sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.changeSessionId()))
            .logout(logout -> logout.logoutUrl("/logout")
                .invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(ref -> ref.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, error) -> {
                    if (req.getRequestURI().startsWith("/api/")) {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}");
                    } else res.sendRedirect("/");
                })
                .accessDeniedHandler((req, res, error) -> {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"code\":\"ACCESS_DENIED\"}");
                }))
            .addFilterAfter(actorFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @RestController
    static class IdentityController {
        @GetMapping("/api/v1/me")
        Map<String, Object> me(HttpServletRequest request) {
            CurrentActor actor = (CurrentActor) request.getAttribute("guitarMentorActor");
            return Map.of("accountId", actor.accountId().toString(),
                "learnerId", actor.learnerId() == null ? "" : actor.learnerId().toString(),
                "role", actor.role());
        }

        @GetMapping("/api/v1/csrf")
        Map<String, String> csrf(CsrfToken csrf) {
            return Map.of("headerName", csrf.getHeaderName(), "token", csrf.getToken());
        }
    }
}
