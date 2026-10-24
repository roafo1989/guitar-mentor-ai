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

    /**
     * Explicit QA profile is only safe with a loopback backend and loopback SMTP.
     * Fail startup if environment variables override the profile for network access.
     */
    @Bean
    org.springframework.boot.ApplicationRunner restrictLocalMailQa(
            @Value("${guitar-mentor.local-auth.qa-mail-capture:false}") boolean qa,
            @Value("${server.address:127.0.0.1}") String bind,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${spring.mail.port:0}") int smtpPort,
            @Value("${guitar-mentor.local-auth.public-origin:}") String origin,
            @Value("${guitar-mentor.security.enabled:true}") boolean securityEnabled,
            @Value("${guitar-mentor.security.local-http:false}") boolean localHttp) {
        return args -> {
            if (qa && (!("127.0.0.1".equals(bind) || "::1".equals(bind))
                    || !("127.0.0.1".equals(smtpHost) || "localhost".equals(smtpHost))
                    || smtpPort != 1025 || !"https://guitar-mentor".equals(origin)
                    || !securityEnabled || localHttp)) {
                throw new IllegalStateException("QA SMTP capture must run on loopback with HTTPS, authentication and local port 1025");
            }
        };
    }

    /** Fail closed before accepting requests when the private combined launcher is misconfigured. */
    @Bean
    org.springframework.boot.ApplicationRunner guardUnifiedLocalAuth(
            org.springframework.core.env.Environment env) {
        return args -> {
            if (!env.getProperty("guitar-mentor.local-auth.unified-local",Boolean.class,false))
                return;
            String bind = env.getProperty("server.address","");
            String origin = env.getProperty("guitar-mentor.local-auth.public-origin","");
            String client = env.getProperty("spring.security.oauth2.client.registration.guitar-mentor.client-id","");
            String clientSecret = env.getProperty("spring.security.oauth2.client.registration.guitar-mentor.client-secret","");
            String redirect = env.getProperty("spring.security.oauth2.client.registration.guitar-mentor.redirect-uri","");
            String smtp = env.getProperty("spring.mail.host","");
            String from = env.getProperty("guitar-mentor.local-auth.mail-from","");
            boolean secureAuth = env.getProperty("guitar-mentor.security.enabled",Boolean.class,false);
            boolean localHttp = env.getProperty("guitar-mentor.security.local-http",Boolean.class,false);
            boolean smtpAuth = env.getProperty("spring.mail.properties.mail.smtp.auth",Boolean.class,false);
            boolean smtpTls = env.getProperty("spring.mail.properties.mail.smtp.starttls.required",Boolean.class,false);
            if (!"127.0.0.1".equals(bind) || !"https://guitar-mentor".equals(origin)
                    || !secureAuth || localHttp || client.isBlank() || clientSecret.isBlank()
                    || !"http://localhost:5173/login/oauth2/code/{registrationId}".equals(redirect)
                    || smtp.isBlank() || from.isBlank() || !smtpAuth || !smtpTls) {
                throw new IllegalStateException("Unified local auth needs HTTPS guitar-mentor, loopback, Google OAuth, SMTP STARTTLS and a verified sender");
            }
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ActorRepository actors, GoogleLinkService googleLinks,
            InvitationService invitations, LocalOAuthBridge oauthBridge,
            org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.client.registration.ClientRegistrationRepository> oidcClients,
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
                    "/api/v1/local-auth/reset",
                    "/api/v1/oauth-bridge/start", "/api/v1/oauth-bridge/consume").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
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
        // Keep local credentials operational when Google OIDC is not configured.
        if (oidcClients.getIfAvailable() != null) {
            http.oauth2Login(login -> login
                .successHandler((req, res, auth) -> {
                    if (!(auth.getPrincipal() instanceof
                            org.springframework.security.oauth2.core.oidc.user.OidcUser oidc)) {
                        req.getSession().invalidate();
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        res.sendRedirect("/?auth=denied");
                        return;
                    }
                    // Google redirects only to a public-TLD domain or localhost, not
                    // to our single-label local HTTPS alias. The validated callback
                    // uses a one-time, original-session-bound ticket to finish there.
                    var callbackSession = req.getSession(false);
                    Object bridgeStart = callbackSession == null ? null
                        : callbackSession.getAttribute("guitarMentorBridgeStartNonce");
                    String bridgeNonce = bridgeStart instanceof String value ? value : null;
                    if (bridgeNonce != null && oauthBridge.linking(bridgeNonce)) {
                        callbackSession.removeAttribute("guitarMentorBridgeStartNonce");
                        try {
                            String ticket = oauthBridge.complete(bridgeNonce, auth);
                            callbackSession.invalidate();
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            res.sendRedirect("https://guitar-mentor/#oauthBridge=" + ticket);
                        } catch (SecurityException ex) {
                            res.sendRedirect("https://guitar-mentor/?auth=bridge_failed");
                        }
                        return;
                    }

                    // A local user requested explicit Google linking after re-entering a password.
                    // Do not fall back to email-based account matching on any failure.
                    var linkingSession=req.getSession(false);
                    if (linkingSession != null && linkingSession.getAttribute("guitarMentorLinkGoogleAccount") != null) {
                        Object id=linkingSession.getAttribute("guitarMentorLinkGoogleAccount");
                        Object version=linkingSession.getAttribute("guitarMentorLinkGoogleVersion");
                        Object started=linkingSession.getAttribute("guitarMentorLinkGoogleStarted");
                        linkingSession.removeAttribute("guitarMentorLinkGoogleAccount");
                        linkingSession.removeAttribute("guitarMentorLinkGoogleVersion");
                        linkingSession.removeAttribute("guitarMentorLinkGoogleStarted");
                        try {
                            if (!(id instanceof java.util.UUID account) || !(version instanceof Long pinned)
                                || !(started instanceof Long timestamp)
                                || System.currentTimeMillis()-timestamp>300000L)
                                throw new SecurityException("GOOGLE_LINK_REJECTED");
                            long current=googleLinks.attachVerifiedGoogle(account,pinned,oidc);
                            linkingSession.setAttribute("guitarMentorAccessVersion",current);
                            linkingSession.setAttribute("guitarMentorAuthenticatedAt",System.currentTimeMillis());
                            res.sendRedirect("/profile?linked=google");
                        } catch (RuntimeException ex) {
                            linkingSession.invalidate();
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            res.sendRedirect("/?auth=link_failed");
                        }
                        return;
                    }
                    // A fresh, server-side OIDC authentication is required to add a password.
                    req.getSession(true).setAttribute("guitarMentorAuthenticatedAt", System.currentTimeMillis());
                    // Self-service Google admission. Never merge identities by matching email.
                    if (actors.findActive(oidc.getIssuer().toString(), oidc.getSubject()).isEmpty()) {
                        try {
                            invitations.admitPublicGoogle(oidc);
                        } catch (RuntimeException ex) {
                            req.getSession().invalidate();
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            res.sendRedirect("/?auth=denied");
                            return;
                        }
                    }
                    var current = actors.findActive(oidc.getIssuer().toString(), oidc.getSubject()).orElseThrow();
                    req.getSession(true).setAttribute("guitarMentorAccessVersion",
                        actors.localVersion(current.accountId()).orElseThrow());
                    if (bridgeNonce != null) {
                        callbackSession.removeAttribute("guitarMentorBridgeStartNonce");
                        try {
                            String ticket = oauthBridge.complete(bridgeNonce, auth);
                            callbackSession.invalidate();
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            res.sendRedirect("https://guitar-mentor/#oauthBridge=" + ticket);
                        } catch (SecurityException ex) {
                            res.sendRedirect("https://guitar-mentor/?auth=bridge_failed");
                        }
                    } else {
                        res.sendRedirect("/");
                    }
                })
                .failureHandler((req, res, ex) -> res.sendRedirect("/?auth=failed")));
        }
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
