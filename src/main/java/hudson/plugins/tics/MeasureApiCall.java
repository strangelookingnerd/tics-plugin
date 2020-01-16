package hudson.plugins.tics;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.joda.time.Instant;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import hudson.plugins.tics.MeasureApiErrorResponse.AlertMessage;
import hudson.plugins.tics.MeasureApiSuccessResponse.Baseline;
import hudson.plugins.tics.MeasureApiSuccessResponse.Run;
import hudson.plugins.tics.MeasureApiSuccessResponse.TqiVersion;

public class MeasureApiCall {
    public static final TypeToken<MeasureApiSuccessResponse<Double>> RESPONSE_DOUBLE_TYPETOKEN = new TypeToken<MeasureApiSuccessResponse<Double>>(){/**/};
    public static final TypeToken<MeasureApiSuccessResponse<TqiVersion>> RESPONSE_TQIVERSION_TYPETOKEN = new TypeToken<MeasureApiSuccessResponse<TqiVersion>>(){/**/};
    public static final TypeToken<MeasureApiSuccessResponse<List<Run>>> RESPONSE_RUNS_TYPETOKEN = new TypeToken<MeasureApiSuccessResponse<List<Run>>>(){/**/};
    public static final TypeToken<MeasureApiSuccessResponse<List<Baseline>>> RESPONSE_BASELINES_TYPETOKEN = new TypeToken<MeasureApiSuccessResponse<List<Baseline>>>(){/**/};

    private final CloseableHttpClient httpclient;
    private final PrintStream logger;
    private final String measureApiUrl;
    private final Optional<StandardUsernamePasswordCredentials> credentials;


    public static class MeasureApiCallException extends Exception {
        private final String message;

        public MeasureApiCallException(final String message) {
            super(message);
            this.message = message;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }


    public MeasureApiCall(final PrintStream logger, final String measureApiUrl, final Optional<StandardUsernamePasswordCredentials> credentials) {
        Preconditions.checkState(measureApiUrl.endsWith("/Measure"));
        this.httpclient = createHttpClient(credentials);
        this.logger = logger;
        this.measureApiUrl = measureApiUrl;
        this.credentials = credentials;
    }

    public void close() {
        try {
            if (httpclient != null) {
                httpclient.close();
            }
        } catch (final IOException e) {
            e.printStackTrace(logger);
        }
    }

    private static CloseableHttpClient createHttpClient(final Optional<StandardUsernamePasswordCredentials> credentials) {
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


    public <T> T execute(final TypeToken<T> typeToken, final String paths, final String metrics) throws MeasureApiCallException {
        return execute(typeToken, paths, metrics, Optional.empty());
    }

    public <T> T execute(final TypeToken<T> typeToken, final String paths, final String metrics, final Optional<Instant> date) throws MeasureApiCallException {
        URIBuilder builder;
        try {
            builder = new URIBuilder(measureApiUrl)
                .setParameter("nodes", paths)
                .setParameter("metrics", metrics);
        } catch (final URISyntaxException e) {
            throw new MeasureApiCallException("Invalid URL: " + e.getMessage());
        }
        if (date.isPresent()) {
            final long seconds = date.get().getMillis() / 1000;
            builder = builder.setParameter("dates", ""+seconds);
        }
        final String url;
        try {
            url = builder.build().toString();
        } catch (final URISyntaxException e) {
            throw new MeasureApiCallException("Invalid URL: " + e.getMessage());
        }


        final HttpGet httpGet = new HttpGet(url);
        // WARNING: we cannot send the X-Requested-With header to get proper Json response in case of error,
        // because tiobeweb (up to 7.5 at least) does not use Basic HTTP Authentication if that header is provided.
        // COMMENTED ON PURPOSE: httpGet.addHeader("X-Requested-With", "JenkinsPlugin"); // X-Requested-With header indicates that tiobeweb API should return Json when an error occurred. Unfortunately, this does not work in 7.4 when wrong section was provided
        logger.println(TicsPublisher.LOGGING_PREFIX + httpGet.toString());

        CloseableHttpResponse response = null;
        final String body;
        try {
            response = httpclient.execute(httpGet);
            body = EntityUtils.toString(response.getEntity());
        } catch (final ConnectException e) {
            IOUtils.closeQuietly(response);
            throw new MeasureApiCallException(e.getMessage());
        } catch (final Exception e) {
            IOUtils.closeQuietly(response);
            throw new MeasureApiCallException(e.toString() /* Includes exception name for more information*/);
        }
        final int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode != HttpStatus.SC_OK) {
            if (statusCode == HttpStatus.SC_UNAUTHORIZED) {
                if (credentials.isPresent()) {
                    IOUtils.closeQuietly(response);
                    throw new MeasureApiCallException("401 Unauthorized - Invalid username/password combination");
                } else {
                    IOUtils.closeQuietly(response);
                    throw new MeasureApiCallException("401 Unauthorized - Project requires authentication, but no credentials provided");
                }
            }

            final Optional<String> formattedError = tryExtractExceptionMessageFromBody(body);
            final String reason = response.getStatusLine().getReasonPhrase();
            if (formattedError.isPresent()) {
                IOUtils.closeQuietly(response);
                throw new MeasureApiCallException(statusCode + " " + reason + " - " + formattedError.get());
            } else {
                // Do not give body in exception, as it might contain html, potentially messing up the Jenkins view.
                logger.println(body);
                IOUtils.closeQuietly(response);
                throw new MeasureApiCallException(statusCode + " " + reason + " - See the build log for a detailed error report.");
            }
        }
        try {
            final T t = new Gson().<T>fromJson(body, typeToken.getType());
            return t;
        } catch(final JsonSyntaxException ex) {
            throw new RuntimeException("Error parsing json: " + body, ex);
        } finally {
            IOUtils.closeQuietly(response);
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
