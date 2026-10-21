package com.imsvet.ims.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.TestcontainersConfiguration;
import com.imsvet.ims.users.application.UserSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-036/ADR-045: a user's sessions are found through the principal index and deleted only after the
 * caller's transaction commits. Uses real Spring Session rows of an unused user id; commits no user data.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserSessionsTest {

    @Autowired
    private UserSessions userSessions;

    @Autowired
    private JdbcIndexedSessionRepository repository;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void endsAllSessionsOfTheUserImmediatelyOutsideATransaction() {
        String first = sessionOf(900_001);
        String second = sessionOf(900_001);
        String other = sessionOf(900_002);

        userSessions.endAllAfterCommit(900_001);

        assertThat(repository.findById(first)).isNull();
        assertThat(repository.findById(second)).isNull();
        assertThat(repository.findById(other)).isNotNull();
        repository.deleteById(other);
    }

    @Test
    void waitsForTheCommitAndEndsNothingOnRollback() {
        String session = sessionOf(900_003);

        transaction.executeWithoutResult(status -> {
            userSessions.endAllAfterCommit(900_003);
            status.setRollbackOnly();
        });
        assertThat(repository.findById(session)).as("rolled back").isNotNull();

        transaction.executeWithoutResult(status -> {
            userSessions.endAllAfterCommit(900_003);
            assertThat(repository.findById(session)).as("before commit").isNotNull();
        });
        assertThat(repository.findById(session)).as("after commit").isNull();
    }

    /** Through the public interface: the JDBC session type itself is package-private. */
    @SuppressWarnings("unchecked")
    private String sessionOf(long userId) {
        SessionRepository<Session> sessions = (SessionRepository<Session>) (SessionRepository<?>) repository;
        Session session = sessions.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, String.valueOf(userId));
        sessions.save(session);
        return session.getId();
    }
}
