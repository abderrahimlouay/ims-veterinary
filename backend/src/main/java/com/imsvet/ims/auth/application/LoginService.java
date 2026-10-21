package com.imsvet.ims.auth.application;

import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.security.SecurityLog;
import com.imsvet.ims.users.application.AccountView;
import com.imsvet.ims.users.application.Credentials;
import com.imsvet.ims.users.application.UserAccounts;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Username/password login (FR-SEC-001, security.md §2.1). The failure message never tells whether the
 * username or the password was wrong; an unknown username still costs one hash comparison.
 */
@Service
public class LoginService {

    private static final String INVALID = "Invalid username or password.";

    private final UserAccounts accounts;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final String unknownUserHash;

    public LoginService(UserAccounts accounts, PasswordEncoder encoder, LoginThrottle throttle) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.throttle = throttle;
        this.unknownUserHash = encoder.encode("unknown-user-timing-equaliser");
    }

    @PreAuthorize("permitAll()")
    @Transactional
    public AccountView login(String username, String password, String clientIp) {
        try {
            throttle.checkAllowed(username, clientIp);
        } catch (BusinessException e) {
            logEvent("login_throttled", username, clientIp);
            throw e;
        }
        Optional<Credentials> credentials = accounts.credentialsFor(username);
        boolean matches = encoder.matches(password,
                credentials.map(Credentials::passwordHash).orElse(unknownUserHash));
        if (credentials.isEmpty() || !matches) {
            throttle.recordFailure(username, clientIp);
            logEvent("login_failed", username, clientIp);
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, INVALID);
        }
        if (!credentials.get().active()) {
            logEvent("login_refused_inactive", username, clientIp);
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED, "This account is disabled.");
        }
        long userId = credentials.get().userId();
        throttle.reset(username, clientIp);
        accounts.recordLogin(userId);
        logEvent("login_succeeded", username, clientIp);
        return accounts.activeAccount(userId).orElseThrow();
    }

    private static void logEvent(String event, String username, String clientIp) {
        SecurityLog.LOG.atInfo().addKeyValue("event", event).addKeyValue("username", username)
                .addKeyValue("clientIp", clientIp).log("Security event {}", event);
    }
}
