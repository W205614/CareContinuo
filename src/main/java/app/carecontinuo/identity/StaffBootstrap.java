package app.carecontinuo.identity;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile("bootstrap")
class StaffBootstrap {
    @Bean
    ApplicationRunner staffBootstrapRunner(Environment env, StaffAdministration staff) {
        return args -> {
            String username = env.getRequiredProperty("CARE_BOOTSTRAP_USERNAME").strip();
            String name = env.getRequiredProperty("CARE_BOOTSTRAP_DISPLAY_NAME").strip();
            String issuer = env.getRequiredProperty("CARE_OIDC_ISSUER").strip();
            String subject = env.getRequiredProperty("CARE_BOOTSTRAP_SUBJECT");
            if (!username.matches("[a-zA-Z0-9._-]{1,80}") || name.isBlank() || name.length() > 100
                    || issuer.isBlank() || issuer.length() > 512 || subject.isBlank() || subject.length() > 255) {
                throw new IllegalArgumentException("Invalid bootstrap staff identity");
            }
            staff.bootstrap(username, name, issuer, subject);
        };
    }
}
