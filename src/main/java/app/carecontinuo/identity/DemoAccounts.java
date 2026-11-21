package app.carecontinuo.identity;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Profile("demo & !preprod")
@Component
@Order(10)
class DemoAccounts implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final String demoPassword;

    DemoAccounts(JdbcClient jdbc, PasswordEncoder passwords,
                 @Value("${CARE_DEMO_PASSWORD:demo-care-2026}") String demoPassword) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("patient-a", "患者甲", "DEMO-A", List.of("PATIENT"));
        seed("patient-b", "患者乙", "DEMO-B", List.of("PATIENT"));
        seed("doctor", "陈医生", null, List.of("DOCTOR", "REVIEWER"));
        seed("followup", "林随访员", null, List.of("FOLLOWUP"));
        seed("followup-b", "周随访员", null, List.of("FOLLOWUP"));
        seed("operations", "排班运营", null, List.of("OPERATIONS"));
    }

    private void seed(String username, String name, String patientReference, List<String> roles) {
        if (patientReference != null) {
            jdbc.sql("insert into patient(reference, display_name) values (?, ?) on conflict do nothing")
                    .params(patientReference, name).update();
        }
        jdbc.sql("""
                insert into app_user(username, password_hash, display_name, patient_id)
                values (?, ?, ?, (select id from patient where reference = ?))
                on conflict (username) do nothing
                """).param(username).param(passwords.encode(demoPassword)).param(name).param(patientReference).update();
        for (String role : roles) {
            jdbc.sql("""
                    insert into user_role(user_id, role) select id, ? from app_user where username = ?
                    on conflict do nothing
                    """).params(role, username).update();
        }
    }
}
