package app.carecontinuo.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class Platform {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
