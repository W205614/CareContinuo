package app.carecontinuo.identity;

import java.security.Principal;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SessionController {
    private final JdbcClient jdbc;
    private final Accounts accounts;

    SessionController(JdbcClient jdbc, Accounts accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @GetMapping("/status")
    Map<String, String> status() {
        jdbc.sql("select 1 from flyway_schema_history where success limit 1").query(Integer.class).single();
        return Map.of("service", "carecontinuo", "status", "ready");
    }

    @GetMapping("/session/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("header", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/session")
    Accounts.Actor session(Principal principal) {
        return accounts.actor(principal);
    }
}
