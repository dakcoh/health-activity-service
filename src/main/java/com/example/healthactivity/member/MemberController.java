package com.example.healthactivity.member;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/members")
public class MemberController {
    private final MemberService members;

    public MemberController(MemberService members) { this.members = members; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse register(@Valid @RequestBody Registration request) {
        return members.register(request.name(), request.nickname(), request.email(), request.password());
    }

    public record Registration(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 50) String nickname,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password) {
        public Registration {
            if (email != null) email = MemberService.normalizeEmail(email);
        }
        @Override public String toString() { return "Registration[redacted]"; }
    }
}
