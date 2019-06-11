package hudson.plugins.tics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Result;
import hudson.plugins.tics.MeasureApiCall.MeasureApiCallException;
import hudson.plugins.tics.TqiPublisherResultBuilder.TqiPublisherResult;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;

public class TicsPublisher extends Recorder {
    static final String LOGGING_PREFIX = "[TICS Publisher] ";
    private final String ticsPath;
    private final String viewerUrl;
    private final String credentialsId;

    /**
     * Constructor arguments are injected by Jenkins, using settings stored through config.jelly.
     */
    @DataBoundConstructor
    public TicsPublisher(final String viewerUrl
            , final String ticsPath
            , final String credentialsId
            ) { // Note: variable names must match names in TicsPublisher/config.jelly
        this.viewerUrl = viewerUrl;
        this.ticsPath = ticsPath;
        this.credentialsId = credentialsId;
    }

    /** Referenced in <tt>config.jelly</tt>. */
    public String getTicsPath() {
        return ticsPath;
    }

    /** Referenced in <tt>config.jelly</tt>. */
    public String getViewerUrl() {
        return viewerUrl;
    }

    /** Referenced in <tt>config.jelly</tt>. */
    public String getCredentialsId() {
        return credentialsId;
    }

    public boolean hasGlobalViewerUrl() {
        return !Strings.isNullOrEmpty(getDescriptor().getViewerUrl());
    }

    public String getGlobalViewerUrl() {
        return getDescriptor().getViewerUrl();
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        final Optional<StandardUsernamePasswordCredentials> credentials = getStandardUsernameCredentials(build.getProject(), credentialsId);
        final String ticsPath1 = Util.replaceMacro(Preconditions.checkNotNull(Strings.emptyToNull(ticsPath), "Path not specified"), build.getEnvironment(listener));

        String measureApiUrl;
        try {
            measureApiUrl = getResolvedTiobewebBaseUrl() + "/api/public/v1/Measure";
        } catch (final InvalidTicsViewerUrl ex) {
            ex.printStackTrace(listener.getLogger());
            return false;
        }

        final TqiPublisherResultBuilder builder = new TqiPublisherResultBuilder(
                listener.getLogger(),
                credentials,
                measureApiUrl,
                ticsPath1);

        final TqiPublisherResult result;
        try {
            result = builder.run();
        } catch (final Exception e) {
            listener.getLogger().println(LOGGING_PREFIX + e.getMessage());
            listener.getLogger().println(Throwables.getStackTraceAsString(e));
            return false;
        } finally {
            builder.close();
        }

        build.addAction(new TicsPublisherBuildAction(build, result));
        build.setResult(Result.SUCCESS); // always succeed

        return true;
    }


    static Optional<StandardUsernamePasswordCredentials> getStandardUsernameCredentials(final AbstractProject<?, ?> project, final String credentialsId) {
        if(Strings.isNullOrEmpty(credentialsId)) {
            return Optional.absent();
        }
        final List<DomainRequirement> domainRequirements = Collections.<DomainRequirement>emptyList();
        final List<StandardUsernamePasswordCredentials> list = CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, project, ACL.SYSTEM, domainRequirements);
        for(final StandardUsernamePasswordCredentials c : list) {
            if(credentialsId.equals(c.getId())) {
                return Optional.of(c);
            }
        }
        return Optional.absent();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /** Note that this method is called only once by Hudson */
    @Override
    public Collection<? extends Action> getProjectActions(final AbstractProject<?, ?> project) {
        return Arrays.asList(new TicsPublisherProjectAction(project));
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
            if(Strings.isNullOrEmpty(url)) {
                return Optional.of(FormValidation.error("Field is required"));
            }
            if (!url.matches("[^:/]+://[^/]+/[^/]+/[^/]+/?")) {
                return Optional.of(FormValidation.errorWithMarkup("URL should be of the form <code>http(s)://hostname/tiobeweb/section</code>"));
            }

            MeasureApiCall apiCall = null;
            try {
                final String measureApiUrl = getMeasureApiUrl(getTiobewebBaseUrlFromGivenUrl(url));
                final PrintStream dummyLogger = new PrintStream(new ByteArrayOutputStream());
                apiCall = new MeasureApiCall(dummyLogger, measureApiUrl, Optional.<StandardUsernamePasswordCredentials>absent());
                apiCall.execute(MeasureApiCall.RESPONSE_DOUBLE_TYPETOKEN, "HIE://", "none");
                return Optional.absent();
            } catch (final MeasureApiCallException e) {
                return Optional.of(FormValidation.errorWithMarkup(e.getMessage()));
            } catch (final InvalidTicsViewerUrl e) {
                return Optional.of(FormValidation.errorWithMarkup(e.getMessage()));
            } finally {
                if(apiCall != null) {
                    apiCall.close();
                }
            }
        }

        private static Optional<FormValidation> checkViewerUrlForWarningsCommon(final String value) {
            String host;
            try {
                host = new URIBuilder(value).getHost();
            } catch (final URISyntaxException e) {
                return Optional.absent();
            }
            if(host.equals("localhost") || host.equals("127.0.0.1")) {
                return Optional.of(FormValidation.warning("Please provide a publicly accessible host, instead of " + host));
            }
            return Optional.absent();
        }

        private FormValidation checkViewerUrlForErrorsOrWarnings(final String value) {
            if(Strings.isNullOrEmpty(value)) {
                return FormValidation.ok();
            }
            Optional<FormValidation> validation = checkViewerUrlForErrorsCommon(value);
            if(validation.isPresent()) {
                return validation.get();
            }
            validation = checkViewerUrlForWarningsCommon(value);
            if(validation.isPresent()) {
                return validation.get();
            }
            return FormValidation.ok();
        }


        /**
         * Form validation of viewerUrl.
         * Uses Jenkinks naming convention "doCheckXXXXX" to bind to entry field.
         * Further reading: https://wiki.jenkins-ci.org/display/JENKINS/Form+Validation
         **/
        public FormValidation doCheckViewerUrl(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String value) throws IOException, InterruptedException {
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
        public FormValidation doCheckGlobalViewerUrl(@QueryParameter final String value) {
            return checkViewerUrlForErrorsOrWarnings(value);
        }


        public FormValidation doCheckTicsPath(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String value, @QueryParameter final String viewerUrl, @QueryParameter final String credentialsId) throws IOException, ServletException, InterruptedException {
            if(Strings.isNullOrEmpty(value)) {
                return FormValidation.error("Field is required");
            }
            if(!value.matches("^[^:/]+://[^/]+/.+$")) {
                return FormValidation.errorWithMarkup("Path should start with <code>hierarchy://project/branch</code>, where <code>hierarchy</code> is either <code>HIE</code> or <code>ORG</code>.");
            }

            final String resolvedViewerUrl = Optional.fromNullable(Strings.emptyToNull(viewerUrl)).or(Strings.nullToEmpty(globalViewerUrl));
            if(checkViewerUrlForErrorsCommon(resolvedViewerUrl).isPresent()) {
                // if an error is present for the URL, do not validate any further
                return FormValidation.ok();
            }

            MeasureApiCall apiCall = null;
            try {
                final EnvVars envvars = project.getEnvironment(null, null);
                final String measureApiUrl = getMeasureApiUrl(getTiobewebBaseUrlFromGivenUrl(Util.replaceMacro(resolvedViewerUrl, envvars)));
                final Optional<StandardUsernamePasswordCredentials> creds = getStandardUsernameCredentials(project, credentialsId);
                final PrintStream dummyLogger = new PrintStream(new ByteArrayOutputStream());
                apiCall = new MeasureApiCall(dummyLogger, measureApiUrl, creds);
                apiCall.execute(MeasureApiCall.RESPONSE_DOUBLE_TYPETOKEN, Util.replaceMacro(value, envvars), "none");
                return FormValidation.ok();
            } catch (final MeasureApiCallException e) {
                return FormValidation.errorWithMarkup(e.getMessage());
            } catch (final InvalidTicsViewerUrl e) {
                return FormValidation.errorWithMarkup(e.getMessage());
            } finally {
                if(apiCall != null) {
                    apiCall.close();
                }
            }
        }

        @Override
        public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
            return true;
        }

        /** Referenced in <tt>global.jelly</tt>. */
        public String getViewerUrl() {
            return globalViewerUrl;
        }


        /** Called by Jenkins to fill credentials dropdown list */
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath final Item context, @QueryParameter final String remote) {
            if (context == null || !context.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel();
            }
            return fillCredentialsIdItems(context, remote);
        }

        public ListBoxModel fillCredentialsIdItems(@Nonnull final Item context, final String remote) {
            List<DomainRequirement> domainRequirements;
            if (remote == null) {
                domainRequirements = Collections.<DomainRequirement>emptyList();
            } else {
                domainRequirements = URIRequirementBuilder.fromUri(remote.trim()).build();
            }
            return new StandardListBoxModel()
                .withEmptySelection()
                .withMatching(
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)
                                ),
                                CredentialsProvider.lookupCredentials(StandardCredentials.class,
                                        context,
                                        ACL.SYSTEM,
                                        domainRequirements)
                        );
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
        Optional<String> optUrl = Optional.fromNullable(Strings.emptyToNull(getViewerUrl()));
        if(!optUrl.isPresent()) {
            optUrl = Optional.fromNullable(Strings.emptyToNull(getDescriptor().getViewerUrl()));
        }
        if(!optUrl.isPresent()) {
            throw new InvalidTicsViewerUrl("TICS Viewer URL was not configured at project level or globally.");
        }
        return getTiobewebBaseUrlFromGivenUrl(optUrl.get());
    }


    public static String getTiobewebBaseUrlFromGivenUrl(final String arg0) throws InvalidTicsViewerUrl {
        final String url = StringUtils.stripEnd(arg0, "/");

        final ArrayList<String> parts = Lists.newArrayList(Splitter.on("/").split(url));
        if(parts.size() < 3) {
            throw new InvalidTicsViewerUrl("Missing host name in TICS Viewer URL");
        }
        if(parts.size() < 5) {
            throw new InvalidTicsViewerUrl("Missing section name in TICS Viewer URL");
        }
        parts.set(3, "tiobeweb"); // change TIOBEPortal to tiobeweb
        return Joiner.on("/").join(parts.subList(0, 5) /* include section name */);
    }



}
