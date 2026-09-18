package com.example.healthactivity.member;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberService {
    private final MemberRepository members;
    private final PasswordEncoder passwords;

    public MemberService(MemberRepository members, PasswordEncoder passwords) {
        this.members = members;
        this.passwords = passwords;
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public MemberResponse register(String name, String nickname, String email, String password) {
        String normalized = normalizeEmail(email);
        if (members.existsByEmail(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 이메일입니다.");
        }
        return MemberResponse.from(members.saveAndFlush(
                new Member(name.strip(), nickname.strip(), normalized, passwords.encode(password))));
    }

    @Transactional(readOnly = true)
    public MemberResponse find(String email) {
        return members.findByEmail(email).map(MemberResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."));
    }
}
