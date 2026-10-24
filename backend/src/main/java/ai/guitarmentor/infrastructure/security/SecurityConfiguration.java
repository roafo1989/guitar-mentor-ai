package ai.guitarmentor.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.web.csrf.CsrfToken;
import jakarta.servlet.http.HttpServletRequest;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    org.springframework.boot.ApplicationRunner rejectInsecureNonLoopback(
            @Value("${guitar-mentor.security.enabled:false}") boolean enabled,
            @Value("${server.address:127.0.0.1}") String bind) {
        return args -> {
            if (!enabled && !(bind.equals("127.0.0.1") || bind.equals("::1")
                    || bind.equals("localhost"))) {
                throw new IllegalStateException("Insecure mode is only allowed on loopback");
            }
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ActorRepository actors,
            @Value("${guitar-mentor.security.enabled:true}") boolean enabled,
            @Value("${guitar-mentor.security.absolute-session-minutes:720}") long absoluteMinutes,
            @Value("${server.address:127.0.0.1}") String bind,
            @Value("${guitar-mentor.security.local-http:false}") boolean localHttp)
            throws Exception {
        if (localHttp && (!enabled || !(bind.equals("127.0.0.1") || bind.equals("::1")
                || bind.equals("localhost")))) {
            throw new IllegalStateException("Local HTTP OAuth test mode requires secure auth and loopback binding");
        }
        if (!enabled) {
            // Fail during SecurityFilterChain creation, before Tomcat can accept requests.
            rejectInsecureNonLoopback(false, bind).run(null);
            // Insecure fallback is reserved for existing localhost-only development.
            return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
        var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax")
                .secure(!localHttp));
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/error", "/login/**", "/oauth2/**", "/").permitAll()
                .requestMatchers("/api/v1/csrf", "/api/v1/local-auth/register", "/api/v1/local-auth/verify",
                    "/api/v1/local-auth/login", "/api/v1/local-auth/request-reset",
                    "/api/v1/local-auth/reset").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .oauth2Login(login -> login
                .successHandler((req, res, auth) -> {
                    if (!(auth.getPrincipal() instanceof
                            org.springframework.security.oauth2.core.oidc.user.OidcUser oidc)) {
                        req.getSession().invalidate();
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        res.sendRedirect("/?auth=denied");
                        return;
                    }
                    // An OIDC-authenticated but unadmitted user can only use invitation activation.
                    if (actors.findActive(oidc.getIssuer().toString(), oidc.getSubject()).isEmpty()) {
                        res.sendRedirect("/?auth=invitation");
                        return;
                    }
                    res.sendRedirect("/");
                })
                .failureHandler((req, res, ex) -> res.sendRedirect("/?auth=failed")))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepo)
                .ignoringRequestMatchers(request -> request.getRequestURI().startsWith("/oauth2/")))
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
            .addFilterAfter(new ActorFilter(actors, java.time.Duration.ofMinutes(absoluteMinutes)),
                    UsernamePasswordAuthenticationFilter.class);
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
        Map<String, String> csrf(HttpServletRequest request) {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token == null) throw new IllegalStateException("CSRF not installed");
            return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
        }
    }
}
