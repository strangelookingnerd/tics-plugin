package hudson.plugins.tics;

import java.io.PrintStream;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.gson.Gson;

import hudson.plugins.tics.MeasureApiCall.MeasureApiCallException;
import hudson.plugins.tics.MeasureApiErrorResponse.AlertMessage;

public abstract class AbstractApiCall {
    private final PrintStream logger;
    private final Optional<StandardUsernamePasswordCredentials> credentials;
    private final String apiCallPrefix;

    public AbstractApiCall(final String apiCallName, final PrintStream logger, final Optional<StandardUsernamePasswordCredentials> credentials) {
        this.apiCallPrefix = apiCallName;
        this.logger = logger;
        this.credentials = credentials;
    }

    protected final CloseableHttpClient createHttpClient() throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
        HttpClientBuilder builder = HttpClients.custom();
        if (credentials.isPresent()) {
            final String username = credentials.get().getUsername();
            final String password = credentials.get().getPassword().getPlainText();
            final CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder = builder.setDefaultCredentialsProvider(credsProvider);
        }
        return builder.build();
    }

    protected void throwIfStatusNotOk(final HttpResponse response, final String body) throws MeasureApiCallException {
        final int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode == HttpStatus.SC_OK) {
            return;
        }

        if (statusCode == HttpStatus.SC_UNAUTHORIZED) {
            if (credentials.isPresent()) {
                throw new MeasureApiCallException(apiCallPrefix + " 401 Unauthorized - Invalid username/password combination");
            } else {
                throw new MeasureApiCallException(apiCallPrefix + " 401 Unauthorized - Project requires authentication, but no credentials provided");
            }
        }

        final Optional<String> formattedError = tryExtractExceptionMessageFromBody(body);
        final String reason = response.getStatusLine().getReasonPhrase();
        if (formattedError.isPresent()) {
            throw new MeasureApiCallException(apiCallPrefix + " " + statusCode + " " + reason + " - " + formattedError.get());
        } else {
            // Do not give body in exception, as it might contain html, potentially messing up the Jenkins view.
            logger.println(body);
            throw new MeasureApiCallException(apiCallPrefix + " " + statusCode + " " + reason + " - See the build log for a detailed error report.");
        }
    }

    private Optional<String> tryExtractExceptionMessageFromBody(final String body) {
        if (body.startsWith("{")) {
            // body is Json
            final MeasureApiErrorResponse out;
            try {
                out = new Gson().fromJson(body, MeasureApiErrorResponse.class);
            } catch(final Exception ex) {
                return Optional.empty();
            }
            if (out == null || out.alertMessages.size() == 0) {
                return Optional.empty();
            }
            final AlertMessage am0 = out.alertMessages.get(0);
            logger.println(am0.stackTrace);
            return Optional.ofNullable(am0.message);
        }

        // Body is html. Happens e.g. in case non-existing section is provided
        final Matcher matcher = Pattern.compile("Exception: ([^\n]+)").matcher(body);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        return Optional.empty();
    }

}
