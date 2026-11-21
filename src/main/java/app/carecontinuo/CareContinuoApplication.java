package app.carecontinuo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class CareContinuoApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(CareContinuoApplication.class, args);
        if (context.getEnvironment().getProperty("care.lifecycle.once", Boolean.class, false)) context.close();
    }
}
