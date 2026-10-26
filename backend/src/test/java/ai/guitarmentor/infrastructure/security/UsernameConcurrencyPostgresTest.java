package ai.guitarmentor.infrastructure.security;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="GUITAR_MENTOR_TEST_POSTGRES",matches="true")
class UsernameConcurrencyPostgresTest {
    @Autowired LocalCredentialService local;
    @Autowired JdbcClient jdbc;
    @Test void onlyOneIndependentTransactionClaimsCanonicalUsername() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8),target="цель"+suffix;
        var accounts=List.of(UUID.randomUUID(),UUID.randomUUID());
        var learners=List.of(UUID.randomUUID(),UUID.randomUUID());
        String hash=Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("123456");
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            for(int i=0;i<2;i++) {
                jdbc.sql("INSERT INTO learners(id,display_name,created_at) VALUES (:id,'QA232 race',now())").param("id",learners.get(i)).update();
                jdbc.sql("INSERT INTO accounts(id,learner_id,role,status) VALUES (:id,:learner,'LEARNER','ACTIVE')").param("id",accounts.get(i)).param("learner",learners.get(i)).update();
                jdbc.sql("INSERT INTO local_credentials(account_id,email_normalized,login_normalized,password_hash,email_verified_at) VALUES (:id,:email,:name,:hash,now())")
                    .param("id",accounts.get(i)).param("email",accounts.get(i)+"@example.test").param("name","race"+i+suffix).param("hash",hash).update();
            }
            List<Future<Boolean>> futures=new ArrayList<>();
            for(UUID account:accounts) futures.add(executor.submit(()->{
                if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("race did not start");
                try {local.changeUsername(account,target.toUpperCase(java.util.Locale.ROOT),"123456");return true;}
                catch(LocalCredentialService.UsernameUnavailable rejected){return false;}
            }));
            start.countDown();
            int winners=0;for(var result:futures)if(result.get(30,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);
            assertEquals(1L,jdbc.sql("SELECT count(*) FROM local_credentials WHERE login_normalized=:name").param("name",target).query(Long.class).single());
        } finally {
            executor.shutdownNow();
            for(UUID id:accounts)jdbc.sql("DELETE FROM accounts WHERE id=:id").param("id",id).update();
            for(UUID id:learners)jdbc.sql("DELETE FROM learners WHERE id=:id").param("id",id).update();
        }
    }
}
