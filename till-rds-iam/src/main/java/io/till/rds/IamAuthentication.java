package io.till.rds;

import java.util.Properties;
import org.postgresql.plugin.AuthenticationPlugin;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * The password of every new connection to a database that authenticates with IAM: a token signed for
 * the connection's host, port and user with whatever AWS credentials the process has — its task role,
 * on ECS — and good for fifteen minutes.
 *
 * <p>pgjdbc constructs one from the connection's properties each time it opens a connection whose URL
 * names it ({@code authenticationPluginClassName=io.till.rds.IamAuthentication}). A token is a SigV4
 * presigned request, made locally: nothing is called to make one. Each connection gets a fresh one,
 * because a pool opens connections at any moment, and a token kept from earlier may have expired.
 */
public final class IamAuthentication implements AuthenticationPlugin {

    private final String host;
    private final int port;
    private final String user;
    private final Tokens tokens;

    /**
     * As pgjdbc constructs it.
     *
     * @param info the connection's properties: the URL's host and port as {@code PGHOST} and
     *     {@code PGPORT}, and the {@code user}
     */
    public IamAuthentication(Properties info) {
        this(info, DefaultTokens.TOKENS);
    }

    IamAuthentication(Properties info, Tokens tokens) {
        this.host = first(info.getProperty("PGHOST"));
        this.port = Integer.parseInt(first(info.getProperty("PGPORT", "5432")));
        this.user = info.getProperty("user");
        this.tokens = tokens;
    }

    @Override
    public char[] getPassword(AuthenticationRequestType type) throws PSQLException {
        try {
            return tokens.sign(host, port, user).toCharArray();
        } catch (RuntimeException e) {
            throw new PSQLException("could not sign an IAM authentication token for " + user + "@" + host + ":" + port,
                    PSQLState.CONNECTION_UNABLE_TO_CONNECT, e);
        }
    }

    /** The first of a comma-separated list: pgjdbc keeps all of a URL's hosts in one property. */
    private static String first(String list) {
        int comma = list.indexOf(',');
        return comma < 0 ? list : list.substring(0, comma);
    }

    /** What signs a token. */
    @FunctionalInterface
    interface Tokens {

        /**
         * @param host the database's host, as the connection names it
         * @param port its port
         * @param user the database user the token is for
         * @return the token, to send as the password
         */
        String sign(String host, int port, String user);

        /**
         * @param credentials what to sign with
         * @param region the database's region
         * @return tokens signed with them
         */
        static Tokens signedWith(AwsCredentialsProvider credentials, Region region) {
            RdsUtilities utilities = RdsUtilities.builder().credentialsProvider(credentials).region(region).build();
            return (host, port, user) ->
                    utilities.generateAuthenticationToken(request -> request.hostname(host).port(port).username(user));
        }
    }

    /**
     * One signer for the process, made the first time a connection needs it: the default chain finds
     * the task role's credentials and refreshes them before they expire, and building it for every
     * connection would fetch them again each time.
     */
    private static final class DefaultTokens {
        static final Tokens TOKENS = Tokens.signedWith(
                DefaultCredentialsProvider.builder().build(), DefaultAwsRegionProviderChain.builder().build().getRegion());
    }
}
