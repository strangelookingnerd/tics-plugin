package hudson.plugins.tics;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.tuple.Pair;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.base.Strings;
import hudson.EnvVars;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;

public class AuthHelper {

    public static <T extends StandardCredentials> Optional<T> getCredentials(final Class<T> clazz, final Job<?, ?> job, final String credentialsId) {
        if (Strings.isNullOrEmpty(credentialsId)) {
            return Optional.empty();
        }
        final List<DomainRequirement> domainRequirements = Collections.<DomainRequirement>emptyList();
        final List<T> list = CredentialsProvider.lookupCredentials(clazz, job, ACL.SYSTEM, domainRequirements);

        for (final T c : list) {
            if (credentialsId.equals(c.getId())) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    public static final Optional<Pair<String, String>> getUsernameAndPasswordFromCredentials(final Job<?, ?> job, final String credentialsId, final EnvVars buildEnv) {
        final Optional<StandardUsernamePasswordCredentials> credentials = AuthHelper.getCredentials(StandardUsernamePasswordCredentials.class, job, credentialsId);
        if (credentials.isPresent()) {
            final Pair<String, String> pairCreds = Pair.of(credentials.get().getUsername(), Secret.toString(credentials.get().getPassword()));
            return Optional.of(pairCreds);
        } else {
            final String ticsAuthToken = AuthHelper.getTicsToken(job, credentialsId, buildEnv);
            final Pair<String, String> decodeTokenToUsernamePassword = AuthHelper.decodeTokenToUsernamePassword(ticsAuthToken);
            return Optional.of(decodeTokenToUsernamePassword);
        }
    }

    /**
     * Looks for the TICSAUTHTOKEN in the StringCredentials list or the environment variables of the node 
     * and returns its value.
     * 
     * */
    public static String getTicsToken(final Job<?, ?> job, final String credentialsId, final EnvVars buildEnv) {
        final String errorMessage = "The provided credentials are of the wrong type. Only two types are supported; username & password and secret text.";
        // TICSAUTHTOKEN can be set through the credentials dropdown available within our plugin 
        // In this case, the credentialsId is present
        if (!Strings.isNullOrEmpty(credentialsId)) {
            return AuthHelper.getCredentials(StringCredentials.class, job, credentialsId)
                    .map(c -> c.getSecret().getPlainText())
                    .orElseThrow(() -> new IllegalArgumentException(errorMessage));
        } else {
            // TICSAUTHTOKEN can also be set as a secret or as a parameter.
            // In both cases, an environment variable is created.
            // If TICSAUTHTOKEN is set as a credentials parameter then the saved format of the environment variable is TICSAUTHTOKEN=credentialsId
            // For all the other cases, the environment variable is of the format TICSAUTHTOKEN=value
            String ticsTokenEnv = buildEnv.get("TICSAUTHTOKEN");
            if (!Strings.isNullOrEmpty(ticsTokenEnv)) {
                return AuthHelper.getCredentials(StringCredentials.class, job, ticsTokenEnv)
                        .map(c -> c.getSecret().getPlainText())
                        .orElse(ticsTokenEnv);
            }
        }

        throw new IllegalArgumentException(errorMessage);
    }

    private static Pair<String, String> decodeTokenToUsernamePassword(final String token) {
        try {
            final byte[] decodedToken = java.util.Base64.getDecoder().decode(token);
            final String decodedTokenStr = new String(decodedToken, StandardCharsets.UTF_8);
            final String[] splitted = decodedTokenStr.split(":");
            if (splitted.length != 2) {
                throw new IllegalStateException("Unexpected number of parts");
            }
            final Pair<String, String> splittedPair = Pair.of(splitted[0], splitted[1]);
            return splittedPair;
        } catch (Exception ex) { 
          throw new IllegalArgumentException("Malformed authentication token. Please make sure you are using a valid token from the TICS Viewer.", ex);
        }
    }

    /** Called by Jenkins to fill credentials dropdown list */
    public static ListBoxModel fillCredentialsDropdown(@AncestorInPath final Item context, @QueryParameter final String credentialsId) {
        final List<DomainRequirement> domainRequirements;
        final CredentialsMatcher credentialsMatcher = CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardCredentials.class));
        final StandardListBoxModel result = new StandardListBoxModel();
        if (context == null) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
        } else {
            if (!context.hasPermission(Item.CONFIGURE)) {
                return result.includeCurrentValue(credentialsId);
            }
        }
        if (credentialsId == null) {
            domainRequirements = Collections.<DomainRequirement>emptyList();
        } else {
            domainRequirements = URIRequirementBuilder.fromUri(credentialsId.trim()).build();
        }
        return result
                .includeEmptyValue()
                .includeMatchingAs(ACL.SYSTEM, context, StandardCredentials.class, domainRequirements, credentialsMatcher);
    }
}
