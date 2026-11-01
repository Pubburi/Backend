package com.pubburi.rehearsal;

import static org.junit.jupiter.api.Assertions.*;
import com.pubburi.pub.model.dto.User;
import com.pubburi.pub.model.service.UserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in test setup only, never packaged or exposed as an API. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named="PUBBURI_JPA_PREPARE_FIXTURE",matches="1")
class JpaFixturePreparationTests {
    @Autowired UserService users;@Autowired JdbcTemplate sql;
    @Test void createOnlyFreshAuthoredUsersThenUseLegacyShaForUpgradeCoverage()throws Exception {
        for(String role:new String[]{"ADMIN","USER"}) {
            String id=System.getenv("PUBBURI_JPA_"+role+"_ID"),pass=System.getenv("PUBBURI_JPA_"+role+"_PASSWORD");
            assertNotNull(id);assertNotNull(pass);assertTrue(id.matches("ci_jpa_[a-zA-Z0-9_]{1,80}"));assertFalse(users.isUsedId(id),"never adopt existing accounts");
            assertEquals(1,users.addUser(new User(id,"Authored JPA "+role,pass,0,role)));
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pass.getBytes(StandardCharsets.UTF_8)));
            assertEquals(1,sql.update("UPDATE pubburi.t_user SET password_hash=? WHERE id=?",sha,id));
        }
    }
}
