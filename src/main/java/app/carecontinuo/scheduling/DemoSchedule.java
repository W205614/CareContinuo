package app.carecontinuo.scheduling;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo & !preprod")
@Order(20)
class DemoSchedule implements ApplicationRunner {
    private final JdbcClient jdbc;

    DemoSchedule(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.sql("insert into department(name) values ('全科门诊'), ('康复门诊') on conflict do nothing").update();
        jdbc.sql("""
                insert into doctor(user_id, department_id, display_name)
                select u.id, d.id, u.display_name from app_user u cross join department d
                where u.username = 'doctor' and d.name = '全科门诊' on conflict(user_id) do nothing
                """).update();
        jdbc.sql("""
                insert into staff_department(user_id, department_id)
                select u.id, d.id from app_user u cross join department d
                where (u.username in ('followup', 'followup-b') and d.name = '全科门诊')
                   or (u.username = 'followup-b' and d.name = '康复门诊') on conflict do nothing
                """).update();
    }
}
