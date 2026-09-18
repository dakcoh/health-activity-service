package com.example.healthactivity.auth;

import com.example.healthactivity.member.MemberResponse;
import com.example.healthactivity.member.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final MemberService members;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contexts,
            SessionAuthenticationStrategy sessions, MemberService members) {
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.sessions = sessions;
        this.members = members;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) { return token; }

    @PostMapping("/login")
    public MemberResponse login(@Valid @RequestBody Login input,
            HttpServletRequest request, HttpServletResponse response) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(input.email(), input.password()));
        sessions.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return members.find(authentication.getName());
    }

    @GetMapping("/me")
    public MemberResponse me(Principal principal) { return members.find(principal.getName()); }

    public record Login(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
        public Login {
            if (email != null) email = MemberService.normalizeEmail(email);
        }
        @Override public String toString() { return "Login[redacted]"; }
    }
}
