package io.till.rds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.util.PSQLException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

class IamAuthenticationTest {

    @Test
    @DisplayName("the password is a token signed for the connection's own host, port and user")
    void theTokenIsForThisConnection() throws PSQLException {
        IamAuthentication plugin = new IamAuthentication(
                connection("db.example.com", "6432", "till"), (host, port, user) -> host + ":" + port + "/" + user);

        char[] password = plugin.getPassword(AuthenticationRequestType.CLEARTEXT_PASSWORD);

        assertEquals("db.example.com:6432/till", new String(password));
    }

    @Test
    @DisplayName("without a port in the URL the token is for PostgreSQL's own, 5432")
    void thePortDefaultsToPostgresqls() throws PSQLException {
        Properties info = connection("db.example.com", null, "till");

        char[] password = new IamAuthentication(info, (host, port, user) -> Integer.toString(port))
                .getPassword(AuthenticationRequestType.CLEARTEXT_PASSWORD);

        assertEquals("5432", new String(password));
    }

    @Test
    @DisplayName("a token is a presigned request to connect, as RDS IAM authentication expects one")
    void aTokenIsAPresignedConnect() {
        // Made-up credentials in the form AWS's own documentation uses; signing is local, so nothing
        // leaves this test.
        IamAuthentication.Tokens tokens = IamAuthentication.Tokens.signedWith(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "wJalrXUtnFEMIEXAMPLEKEY")),
                Region.US_WEST_2);

        String token = tokens.sign("db.example.com", 5432, "till");

        assertTrue(token.startsWith("db.example.com:5432/?"), token);
        assertTrue(token.contains("Action=connect"), token);
        assertTrue(token.contains("DBUser=till"), token);
        assertTrue(token.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), token);
        assertTrue(token.contains("us-west-2%2Frds-db%2Faws4_request"), token);
    }

    @Test
    @DisplayName("a token that cannot be signed fails the connection, saying for whom")
    void aSigningFailureFailsTheConnection() {
        IamAuthentication plugin = new IamAuthentication(connection("db.example.com", "5432", "till"), (host, port, user) -> {
            throw new IllegalStateException("no credentials");
        });

        PSQLException e = assertThrows(PSQLException.class,
                () -> plugin.getPassword(AuthenticationRequestType.CLEARTEXT_PASSWORD));

        assertTrue(e.getMessage().contains("till@db.example.com:5432"), e.getMessage());
    }

    private static Properties connection(String host, String port, String user) {
        // What pgjdbc hands a plugin: the connection's properties, the URL's host and port among them.
        Properties info = new Properties();
        info.setProperty("PGHOST", host);
        if (port != null) {
            info.setProperty("PGPORT", port);
        }
        info.setProperty("user", user);
        return info;
    }
}
