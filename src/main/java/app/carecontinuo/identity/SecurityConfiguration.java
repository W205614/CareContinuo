package app.carecontinuo.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@ConditionalOnWebApplication
class SecurityConfiguration {
    private static final String AUTH_VERSION = SecurityConfiguration.class.getName() + ".authVersion";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcTemplate jdbc, @Value("${care.auth.patient-password-only:false}") boolean patientsOnly) {
        var manager = new JdbcUserDetailsManager(jdbc.getDataSource());
        manager.setUsersByUsernameQuery("""
                select username, password_hash, enabled from app_user
                where username = ? and password_hash is not null
                """ + (patientsOnly ? " and patient_id is not null" : ""));
        manager.setAuthoritiesByUsernameQuery("""
                select u.username, concat('ROLE_', r.role) from user_role r
                join app_user u on u.id = r.user_id where u.username = ?
                """);
        return manager;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, Accounts accounts,
                                    ObjectProvider<ClientRegistrationRepository> clients,
                                    @Value("${care.auth.required-acr:}") String requiredAcr,
                                    @Value("${CARE_PREPROD_ORIGIN:}") String origin) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/status", "/api/v1/session/csrf", "/api/v1/session/options",
                                "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .formLogin(login -> login.loginProcessingUrl("/api/v1/session/login")
                        .successHandler((request, response, authentication) -> {
                            remember(request, accounts, authentication);
                            response.setStatus(204);
                        })
                        .failureHandler((request, response, exception) -> response.sendError(401))
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/api/v1/session/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .addFilterBefore(new SessionVersionFilter(accounts), AuthorizationFilter.class)
                .requestCache(cache -> cache.disable());

        var registrations = clients.getIfAvailable();
        if (registrations != null) {
            if (requiredAcr.isBlank()) throw new IllegalStateException("Staff OIDC requires care.auth.required-acr");
            var loader = new OidcUserService();
            var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(builder ->
                    builder.additionalParameters(parameters -> parameters.put("acr_values", requiredAcr)));
            http.oauth2Login(login -> login
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                    .userInfoEndpoint(info -> info.oidcUserService(request -> {
                        var token = request.getIdToken();
                        if (!requiredAcr.equals(token.getClaimAsString("acr"))
                                || accounts.oidcIdentity(token.getIssuer().toString(), token.getSubject()).isEmpty()) {
                            throw new OAuth2AuthenticationException("staff_login_denied");
                        }
                        return loader.loadUser(request);
                    }))
                    .successHandler((request, response, authentication) -> {
                        remember(request, accounts, authentication);
                        response.sendRedirect(origin + "/workspace");
                    })
                    .failureHandler((request, response, failure) -> response.sendRedirect(origin + "/?staff-login=denied")));
        }
        return http.build();
    }

    private static void remember(HttpServletRequest request, Accounts accounts, Authentication authentication) {
        request.getSession().setAttribute(AUTH_VERSION, accounts.identity(authentication).authVersion());
    }

    private static final class SessionVersionFilter extends OncePerRequestFilter {
        private final Accounts accounts;

        private SessionVersionFilter(Accounts accounts) {
            this.accounts = accounts;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                var session = request.getSession(false);
                var identity = accounts.findIdentity(authentication);
                if (session == null || identity.isEmpty()
                        || !Long.valueOf(identity.get().authVersion()).equals(session.getAttribute(AUTH_VERSION))) {
                    if (session != null) session.invalidate();
                    SecurityContextHolder.clearContext();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                request.setAttribute(Accounts.class.getName(), identity.get());
            }
            chain.doFilter(request, response);
        }
    }
}
