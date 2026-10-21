package org.example.ohara.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Creates verified accounts directly in the database and signs them in, for tests that aren't about registration. */
public class TestUsers {

    public static final String PASSWORD = "secret1234";

    /** A signed-in test user. */
    public record TestUser(UUID id, String email, String token) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final MockMvc mockMvc;
    private String encodedPassword;

    public TestUsers(JdbcClient jdbc, PasswordEncoder passwordEncoder, MockMvc mockMvc) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.mockMvc = mockMvc;
    }

    public TestUser user() throws Exception {
        return create(false);
    }

    public TestUser platformAdmin() throws Exception {
        return create(true);
    }

    private TestUser create(boolean platformAdmin) throws Exception {
        UUID userId = UUID.randomUUID();
        String email = "t-" + userId + "@example.com";
        jdbc.sql("""
                INSERT INTO users (id, email, first_name, last_name, status, email_verified_at)
                VALUES (:id, :email, 'Test', 'User', 'ACTIVE', now())
                """).param("id", userId).param("email", email).update();
        jdbc.sql("INSERT INTO user_profile (user_id, display_name, first_name, last_name) VALUES (:id, 'Test User', 'Test', 'User')")
            .param("id", userId).update();
        grant(userId, "ROLE_USER");
        if (platformAdmin) {
            grant(userId, "ROLE_PLATFORM_ADMIN");
        }
        UUID identityId = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth_identity (id, user_id, provider, identifier, verified_at) VALUES (:iid, :id, 'local', :email, now())")
            .param("iid", identityId).param("id", userId).param("email", email).update();
        jdbc.sql("INSERT INTO password_credential (id, auth_identity_id, encoded_password) VALUES (:cid, :iid, :pw)")
            .param("cid", UUID.randomUUID()).param("iid", identityId).param("pw", encodedPassword()).update();

        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new TestUser(userId, email, JsonPath.read(body, "$.accessToken"));
    }

    private void grant(UUID userId, String role) {
        jdbc.sql("INSERT INTO user_role (id, user_id, name) VALUES (:rid, :id, :role)")
            .param("rid", UUID.randomUUID()).param("id", userId).param("role", role).update();
    }

    // BCrypt is deliberately slow; hash the shared test password once.
    private synchronized String encodedPassword() {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(PASSWORD);
        }
        return encodedPassword;
    }
}
