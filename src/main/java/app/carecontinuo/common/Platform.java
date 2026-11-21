package app.carecontinuo.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class Platform {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean("taskScheduler")
    ThreadPoolTaskScheduler businessScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("business-work-");
        return scheduler;
    }

    @Bean("agentScheduler")
    ThreadPoolTaskScheduler agentScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("agent-work-");
        return scheduler;
    }
}
