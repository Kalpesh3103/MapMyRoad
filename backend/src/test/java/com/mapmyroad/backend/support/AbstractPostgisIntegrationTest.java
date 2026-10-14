package com.mapmyroad.backend.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests against a real PostgreSQL + PostGIS database (TESTING_STRATEGY.md section 5.2).
 * Tests are skipped, not failed, when Docker is not available.
 *
 * <p>Singleton container: started once per JVM and stopped by Testcontainers' Ryuk at exit. It is deliberately
 * not a {@code @Container} field: the JUnit extension would stop and restart it for every test class, while Spring
 * caches one application context (and its connection pool) across classes, so the second class would talk to a
 * dead port.
 *
 * <p>Testcontainers 2.0 moved the module classes to {@code org.testcontainers.<module>} packages (artifact
 * {@code testcontainers-postgresql}); the new {@link PostgreSQLContainer} is no longer generic.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgisIntegrationTest {

    /** Same major versions as production (DEPLOYMENT.md section 4). */
    private static final DockerImageName POSTGIS =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGIS);

    static {
        // Guarded so that loading this class without Docker never throws; @Testcontainers skips the tests then.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }
}
