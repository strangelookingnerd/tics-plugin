package hudson.plugins.tics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.tics.MeasureApiCall.MeasureApiCallException;
import hudson.plugins.tics.TicsQualityGate.QualityGateResult;
import hudson.plugins.tics.TqiPublisherResultBuilder.TqiPublisherResult;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

public class TicsPublisher extends Recorder implements SimpleBuildStep {
    static final String LOGGING_PREFIX = "[TICS Publisher] ";
    private final String ticsPath;
    private final String viewerUrl;
    private final String credentialsId;
    private final boolean checkQualityGate;
    private final boolean failIfQualityGateFails;

    /**
     * Constructor arguments are injected by Jenkins, using settings stored through config.jelly.
     */
    @DataBoundConstructor
    public TicsPublisher(final String viewerUrl
            , final String ticsPath
            , final String credentialsId
            , final boolean checkQualityGate
            , final boolean failIfQualityGateFails
    ) { // Note: variable names must match names in TicsPublisher/config.jelly
        this.viewerUrl = viewerUrl;
        this.ticsPath = ticsPath;
        this.credentialsId = credentialsId;
        this.checkQualityGate = checkQualityGate;
        this.failIfQualityGateFails = failIfQualityGateFails;
    }

    /** Referenced in <code>config.jelly</code>. */
    public String getTicsPath() {
        return ticsPath;
    }

    /** Referenced in <code>config.jelly</code>. */
    public String getViewerUrl() {
        return viewerUrl;
    }

    /** Referenced in <code>config.jelly</code>. */
    public String getCredentialsId() {
        return credentialsId;
    }

    public boolean hasGlobalViewerUrl() {
        return !Strings.isNullOrEmpty(getDescriptor().getViewerUrl());
    }

    public String getGlobalViewerUrl() {
        return getDescriptor().getViewerUrl();
    }

    /** Referenced in <code>config.jelly</code>. */
    public boolean getCheckQualityGate() {
        return checkQualityGate;
    }

    /** Referenced in <code>config.jelly</code>. */
    public boolean getFailIfQualityGateFails() {
        return failIfQualityGateFails;
    }

    @Override
    public void perform(@Nonnull final Run<?, ?> run, @Nonnull final FilePath workspace, @Nonnull final Launcher launcher, @Nonnull final TaskListener listener) throws IOException, RuntimeException, InterruptedException {
        final Optional<StandardUsernamePasswordCredentials> credentials = getStandardUsernameCredentials(run.getParent(), credentialsId);
        final String ticsPath1 = Util.replaceMacro(Preconditions.checkNotNull(Strings.emptyToNull(ticsPath), "Path not specified"), run.getEnvironment(listener));

        final String measureApiUrl;
        final String qualityGateUrl;
        final String tiobeWebBaseUrl;
        try {
            measureApiUrl = getResolvedTiobewebBaseUrl() + "/api/public/v1/Measure";
            qualityGateUrl = getResolvedTiobewebBaseUrl() + "/api/public/v1/QualityGateStatus";
            tiobeWebBaseUrl = getResolvedTiobewebBaseUrl();
        } catch (final InvalidTicsViewerUrl ex) {
            ex.printStackTrace(listener.getLogger());
            throw new IllegalArgumentException(LOGGING_PREFIX + "Invalid TICS Viewer URL", ex);
        }

        final TqiPublisherResultBuilder builder = new TqiPublisherResultBuilder(
                listener.getLogger(),
                credentials,
                measureApiUrl,
                ticsPath1);

        TqiPublisherResult tqiLabelResult;
        QualityGateResult qualityGateResult = null;

        try {
            tqiLabelResult = builder.run();
        } catch (final Exception e) {
            listener.getLogger().println(LOGGING_PREFIX + e.getMessage());
            listener.getLogger().println(Throwables.getStackTraceAsString(e));
            tqiLabelResult = null;
        }

        if (checkQualityGate) {
            final TicsQualityGate qualityGate = new TicsQualityGate(qualityGateUrl, tiobeWebBaseUrl, failIfQualityGateFails, ticsPath1, credentials, run, listener);
            try {
                qualityGateResult = qualityGate.createQualityGateResult();
                // Add action only when Quality Gate is enabled
            } catch (final Exception e) {
                qualityGateResult = null;
                if (failIfQualityGateFails) {
                    // Mark the run as failure when failIfQualityGatingFails is set, and any exception was thrown during the Quality Gate calculation
                    run.setResult(Result.FAILURE);
                }
                listener.getLogger().println(Throwables.getStackTraceAsString(e));
            }
        }

        run.addAction(new TicsPublisherBuildAction(run, tqiLabelResult, qualityGateResult, tiobeWebBaseUrl));
        run.setResult(Result.SUCCESS); // always succeed
    }


    static Optional<StandardUsernamePasswordCredentials> getStandardUsernameCredentials(final Job<?, ?> job, final String credentialsId) {
        if (Strings.isNullOrEmpty(credentialsId)) {
            return Optional.empty();
        }
        final List<DomainRequirement> domainRequirements = Collections.<DomainRequirement>emptyList();
        final List<StandardUsernamePasswordCredentials> list = CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, job, ACL.SYSTEM, domainRequirements);
        for (final StandardUsernamePasswordCredentials c : list) {
            if (credentialsId.equals(c.getId())) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     * Descriptor is an object that has metadata about a Describable object, and
     * also serves as a factory (in a way this relationship is similar to
     * Object/Class relationship. A Descriptor/Describable combination is used
     * throughout in Hudson to implement a configuration/extensibility
     * mechanism.
     */
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public String globalViewerUrl;

        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Publish TICS results";
        }

        @Override
        public boolean configure(final StaplerRequest staplerRequest, final JSONObject json) throws FormException {
            // to persist global configuration information set that to properties and call save().
            this.globalViewerUrl = json.getString("globalViewerUrl");
            save();
            return true; // indicate that everything is good so far
        }

        /** Helper method to check whether URL points to a TICS Viewer, in which case it returns Optional.absent(). */
        private static Optional<FormValidation> checkViewerUrlForErrorsCommon(final String url) {
            if (Strings.isNullOrEmpty(url)) {
                return Optional.of(FormValidation.error("Field is required"));
            }
            if (!url.matches("[^:/]+://[^/]+/[^/]+/[^/]+/?")) {
                return Optional.of(FormValidation.errorWithMarkup("URL should be of the form <code>http(s)://hostname/tiobeweb/section</code>"));
            }

            try {
                final String measureApiUrl = getMeasureApiUrl(getTiobewebBaseUrlFromGivenUrl(url));
                final PrintStream dummyLogger = new PrintStream(new ByteArrayOutputStream());
                final MeasureApiCall apiCall = new MeasureApiCall(dummyLogger, measureApiUrl, Optional.empty());
                apiCall.execute(MeasureApiCall.RESPONSE_DOUBLE_TYPETOKEN, "HIE://", "none");
                return Optional.empty();
            } catch (final MeasureApiCallException e) {
                return Optional.of(FormValidation.errorWithMarkup(e.getMessage()));
            } catch (final InvalidTicsViewerUrl e) {
                return Optional.of(FormValidation.errorWithMarkup(e.getMessage()));
            }
        }

        private static Optional<FormValidation> checkViewerUrlForWarningsCommon(final String value) {
            final String host;
            try {
                host = new URIBuilder(value).getHost();
            } catch (final URISyntaxException e) {
                return Optional.empty();
            }
            if (host.equals("localhost") || host.equals("127.0.0.1")) {
                return Optional.of(FormValidation.warning("Please provide a publicly accessible host, instead of " + host));
            }
            return Optional.empty();
        }

        private FormValidation checkViewerUrlForErrorsOrWarnings(final String value) {
            if (Strings.isNullOrEmpty(value)) {
                return FormValidation.ok();
            }
            Optional<FormValidation> validation = checkViewerUrlForErrorsCommon(value);
            if (validation.isPresent()) {
                return validation.get();
            }
            validation = checkViewerUrlForWarningsCommon(value);
            if (validation.isPresent()) {
                return validation.get();
            }
            return FormValidation.ok();
        }


        /**
         * Form validation of viewerUrl.
         * Uses Jenkinks naming convention "doCheckXXXXX" to bind to entry field.
         * Further reading: https://wiki.jenkins-ci.org/display/JENKINS/Form+Validation
         **/
        @POST
        public FormValidation doCheckViewerUrl(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String value) throws IOException, InterruptedException {
            if (project == null) { // no context
                return FormValidation.ok();
            }
            project.checkPermission(Item.CONFIGURE);

            final EnvVars envvars = project.getEnvironment(null, null);
            if (Strings.isNullOrEmpty(value)) {
                final String globalViewerUrl2 = Util.replaceMacro(Strings.nullToEmpty(this.globalViewerUrl), envvars);
                // Check whether correctly defined at global level
                if (Strings.isNullOrEmpty(globalViewerUrl2)) {
                    return FormValidation.error("Field is required");
                } else {
                    final Optional<FormValidation> error = checkViewerUrlForErrorsCommon(globalViewerUrl2);
                    if (error.isPresent()) {
                        return FormValidation.errorWithMarkup("Global setting (" + globalViewerUrl2 + ") is invalid: " + error.get().getMessage());
                    } else {
                        return FormValidation.okWithMarkup("Using global setting: " + globalViewerUrl2);
                    }
                }
            }
            return checkViewerUrlForErrorsOrWarnings(Util.replaceMacro(value, envvars));
        }

        /**
         * Form validation of globalViewerUrl.
         */
        @POST
        public FormValidation doCheckGlobalViewerUrl(@AncestorInPath final Item item, @QueryParameter final String value) {
            if (item == null) { // no context
                return FormValidation.ok();
            }
            item.checkPermission(Item.CONFIGURE);
            return checkViewerUrlForErrorsOrWarnings(value);
        }

        @POST
        public FormValidation doCheckTicsPath(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String value, @QueryParameter final String viewerUrl, @QueryParameter final String credentialsId) throws IOException, InterruptedException {
            if (project == null) { // no context
                return FormValidation.ok();
            }
            project.checkPermission(Item.CONFIGURE);

            if (Strings.isNullOrEmpty(value)) {
                return FormValidation.error("Field is required");
            }
            if (!value.matches("^[^:/]+://[^/]+/.+$")) {
                return FormValidation.errorWithMarkup("Path should start with <code>hierarchy://project/branch</code>, where <code>hierarchy</code> is either <code>HIE</code> or <code>ORG</code>.");
            }

            final String resolvedViewerUrl = Optional.ofNullable(Strings.emptyToNull(viewerUrl)).orElse(Strings.nullToEmpty(globalViewerUrl));
            if (checkViewerUrlForErrorsCommon(resolvedViewerUrl).isPresent()) {
                // if an error is present for the URL, do not validate any further
                return FormValidation.ok();
            }

            try {
                final EnvVars envvars = project.getEnvironment(null, null);
                final String measureApiUrl = getMeasureApiUrl(getTiobewebBaseUrlFromGivenUrl(Util.replaceMacro(resolvedViewerUrl, envvars)));
                final Optional<StandardUsernamePasswordCredentials> creds = getStandardUsernameCredentials(project, credentialsId);
                final PrintStream dummyLogger = new PrintStream(new ByteArrayOutputStream());
                final MeasureApiCall apiCall = new MeasureApiCall(dummyLogger, measureApiUrl, creds);
                apiCall.execute(MeasureApiCall.RESPONSE_DOUBLE_TYPETOKEN, Util.replaceMacro(value, envvars), "none");
                return FormValidation.ok();
            } catch (final MeasureApiCallException e) {
                return FormValidation.errorWithMarkup(e.getMessage());
            } catch (final InvalidTicsViewerUrl e) {
                return FormValidation.errorWithMarkup(e.getMessage());
            }
        }

        @Override
        public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
            return true;
        }

        /** Referenced in <code>global.jelly</code>. */
        public String getViewerUrl() {
            return globalViewerUrl;
        }

        /** Called by Jenkins to fill credentials dropdown list */
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath final Item context, @QueryParameter final String credentialsId) {
            final List<DomainRequirement> domainRequirements;
            final CredentialsMatcher credentialsMatcher = CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));
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

    static class InvalidTicsViewerUrl extends Exception {
        public InvalidTicsViewerUrl(final String msg) {
            super(msg);
        }
    }

    public static String getMeasureApiUrl(final String tiobewebBaseUrl) {
        return tiobewebBaseUrl + "/api/public/v1/Measure";
    }

    /** Returns the tiobeweb URL based on the configured viewer url, tries project setting first, then global setting.
     * Example: "http://192.168.1.88:42506/tiobeweb/default"
     * @throws InvalidTicsViewerUrl */
    public String getResolvedTiobewebBaseUrl() throws InvalidTicsViewerUrl {
        Optional<String> optUrl = Optional.ofNullable(Strings.emptyToNull(getViewerUrl()));
        if (!optUrl.isPresent()) {
            optUrl = Optional.ofNullable(Strings.emptyToNull(getDescriptor().getViewerUrl()));
        }
        if (!optUrl.isPresent()) {
            throw new InvalidTicsViewerUrl("TICS Viewer URL was not configured at project level or globally.");
        }
        return getTiobewebBaseUrlFromGivenUrl(optUrl.get());
    }


    public static String getTiobewebBaseUrlFromGivenUrl(final String arg0) throws InvalidTicsViewerUrl {
        final String url = StringUtils.stripEnd(arg0, "/");

        final ArrayList<String> parts = Lists.newArrayList(Splitter.on("/").split(url));
        if (parts.size() < 3) {
            throw new InvalidTicsViewerUrl("Missing host name in TICS Viewer URL");
        }
        if (parts.size() < 5) {
            throw new InvalidTicsViewerUrl("Missing section name in TICS Viewer URL");
        }
        parts.set(3, "tiobeweb"); // change TIOBEPortal to tiobeweb
        return Joiner.on("/").join(parts.subList(0, 5) /* include section name */);
    }



}
