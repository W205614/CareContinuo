package app.carecontinuo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CareContinuoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CareContinuoApplication.class, args);
    }
}
