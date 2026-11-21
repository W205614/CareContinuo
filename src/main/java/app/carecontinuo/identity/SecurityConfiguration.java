package app.carecontinuo.identity;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcTemplate jdbc) {
        var manager = new JdbcUserDetailsManager(jdbc.getDataSource());
        manager.setUsersByUsernameQuery("select username, password_hash, enabled from app_user where username = ?");
        manager.setAuthoritiesByUsernameQuery("""
                select u.username, concat('ROLE_', r.role) from user_role r
                join app_user u on u.id = r.user_id where u.username = ?
                """);
        return manager;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/status", "/api/v1/session/csrf").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .formLogin(login -> login.loginProcessingUrl("/api/v1/session/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> response.sendError(401))
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/api/v1/session/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .requestCache(cache -> cache.disable())
                .build();
    }
}
