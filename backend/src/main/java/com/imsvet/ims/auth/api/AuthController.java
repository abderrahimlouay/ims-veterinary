package com.imsvet.ims.auth.api;

import com.imsvet.ims.auth.application.CurrentUserService;
import com.imsvet.ims.auth.application.LoginService;
import com.imsvet.ims.auth.application.PasswordChangeService;
import com.imsvet.ims.users.application.AccountView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.1. Login is the only public endpoint (BR-SEC-001); every state change needs the CSRF header.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final LoginService login;
    private final CurrentUserService currentUser;
    private final PasswordChangeService passwords;
    private final AuthSessions sessions;

    AuthController(LoginService login, CurrentUserService currentUser, PasswordChangeService passwords,
            AuthSessions sessions) {
        this.login = login;
        this.currentUser = currentUser;
        this.passwords = passwords;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    CurrentUserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        AccountView account = login.login(body.username(), body.password(), request.getRemoteAddr());
        sessions.establish(request, account.id());
        return CurrentUserResponse.of(account);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.end(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    CurrentUserResponse me() {
        return CurrentUserResponse.of(currentUser.me());
    }

    @PostMapping("/change-password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        passwords.changeOwnPassword(body.currentPassword(), body.newPassword());
        sessions.rotate(request);
        return ResponseEntity.noContent().build();
    }
}
