package io.github.bassemzohdy.openworkflow.agentic.identity;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * How the real-Keycloak integration tests find their server (docs/identity.md section 9.4, CI contract in
 * docs/handoffs/gpt/WP-102.md). Settings come from system properties ({@code -Dkeycloak.url=...}, which Maven passes to
 * Failsafe) or from the environment.
 *
 * <ul>
 *   <li>{@code keycloak.required=true} (CI): a missing server, a wrong profile or an unreachable server is a
 *       <strong>failure</strong>, never a skip. A skipped real-provider test is not evidence.</li>
 *   <li>otherwise (a developer build without a server): the tests are skipped.</li>
 * </ul>
 */
final class KeycloakSupport {

    /** What the tests need to know about the server. */
    record Config(String url, String profile, String adminUser, String adminPassword) { }

    private KeycloakSupport() {
    }

    /** The server configuration for {@code profile} ("standard" or "delegation"), or a skip / failure as above. */
    static Config require(String profile) {
        String url = setting("keycloak.url", "KEYCLOAK_URL", null);
        boolean required = Boolean.parseBoolean(setting("keycloak.required", "KEYCLOAK_REQUIRED", "false"));
        if (url == null || url.isBlank()) {
            if (required) {
                fail("keycloak.required=true but no keycloak.url / KEYCLOAK_URL: the real-provider test cannot be skipped");
            }
            assumeTrue(false, "no Keycloak server configured (set keycloak.url); real-provider tests skipped");
        }
        String declared = setting("keycloak.profile", "KEYCLOAK_PROFILE", profile);
        if (!profile.equals(declared)) {
            fail("this class tests the '" + profile + "' profile but keycloak.profile is '" + declared + "'");
        }
        return new Config(url,
                profile,
                setting("keycloak.admin.user", "KEYCLOAK_ADMIN_USER", setting(null, "KEYCLOAK_ADMIN", "admin")),
                setting("keycloak.admin.password", "KEYCLOAK_ADMIN_PASSWORD", "admin"));
    }

    private static String setting(String property, String variable, String fallback) {
        String value = property == null ? null : System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(variable);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The PostgreSQL database of the grants (migrated), as every other database test of the application uses it. */
    static DataSource dataSource() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic");
        String user = System.getenv().getOrDefault("DB_USER", "postgres");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "postgres");
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }
}
