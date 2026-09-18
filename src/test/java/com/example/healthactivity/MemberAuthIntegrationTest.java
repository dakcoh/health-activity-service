package com.example.healthactivity;

import com.example.healthactivity.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;

    private static final String REGISTRATION = """
            {"name":"홍길동","nickname":"walker","email":" Walker@Example.com ","password":"password123!"}
            """;
    private static final String LOGIN = """
            {"email":"walker@example.com","password":"password123!"}
            """;

    @BeforeEach void clean() { members.deleteAll(); }

    @Test
    void registrationNormalizesEmailAndHashesPassword() throws Exception {
        register();
        var member = members.findByEmail("walker@example.com").orElseThrow();
        assertThat(member.getPasswordHash()).isNotEqualTo("password123!");
        assertThat(encoder.matches("password123!", member.getPasswordHash())).isTrue();
        mvc.perform(post("/api/members").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRATION.replace(" Walker@Example.com ", "walker@example.com")))
                .andExpect(status().isConflict()).andExpect(jsonPath("status").value(409));
        assertThat(members.count()).isEqualTo(1);
    }

    @Test
    void invalidInputAndMissingCsrfAreRejected() throws Exception {
        mvc.perform(post("/api/members").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"nickname\":\"x\",\"email\":\"invalid\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));
        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON).content(REGISTRATION))
                .andExpect(status().isForbidden()).andExpect(jsonPath("status").value(403));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        assertThat(members.count()).isZero();
    }

    @Test
    void invalidCredentialsReturnSameError() throws Exception {
        register();
        for (String input : new String[] { LOGIN.replace("password123!", "incorrect"),
                LOGIN.replace("walker@example.com", "missing@example.com") }) {
            mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("status").value(401))
                    .andExpect(jsonPath("detail").value("이메일 또는 비밀번호가 올바르지 않습니다."));
        }
    }

    @Test
    void realCsrfLoginSessionRotationAndLogout() throws Exception {
        register();
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String originalId = session.getId();
        JsonNode token = new JsonMapper().readTree(csrfResult.getResponse().getContentAsString());
        mvc.perform(post("/api/auth/login").session(session)
                        .header(token.get("headerName").asText(), token.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk()).andExpect(jsonPath("email").value("walker@example.com"))
                .andExpect(jsonPath("passwordHash").doesNotExist());
        assertThat(session.getId()).isNotEqualTo(originalId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("nickname").value("walker"));
        mvc.perform(post("/api/auth/logout").session(session)
                        .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isForbidden());
        var refreshed = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        token = new JsonMapper().readTree(refreshed.getResponse().getContentAsString());
        mvc.perform(post("/api/auth/logout").session(session)
                        .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    private void register() throws Exception {
        mvc.perform(post("/api/members").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REGISTRATION))
                .andExpect(status().isCreated()).andExpect(jsonPath("id").isNumber())
                .andExpect(jsonPath("email").value("walker@example.com"))
                .andExpect(jsonPath("password").doesNotExist()).andExpect(jsonPath("passwordHash").doesNotExist());
    }
}
