package hudson.plugins.tics;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.sound.sampled.Line;

import org.apache.commons.text.StringEscapeUtils;
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
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.tics.TicsPublisher.InvalidTicsViewerUrl;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

public class TicsAnalyzer extends Builder implements SimpleBuildStep {
    static final String LOGGING_PREFIX = "[TICS Analyzer] ";

    // DO NOT RENAME THESE FIELDS, as they are serialized (by Jenkins) in jobs/<project>/config.xml
    public final String ticsPath;
    public final String ticsConfiguration;
    public final String projectName;
    public final String branchName;
    public final String branchDirectory;
    public final String environmentVariables;
    public final boolean createTmpdir;
    public final String tmpdir;
    public final String extraArguments;
    public final Metrics calc;
    public final Metrics recalc;
    public boolean installTics;
    public final String ticsEnvVariable;
//    public final String credentialsId;

    /**
     * This annotation tells Hudson to call this constructor, with values from the configuration form page with matching parameter names.
     * See https://wiki.jenkins-ci.org/display/JENKINS/Basic+guide+to+Jelly+usage+in+Jenkins for explanation of DataBoundConstructor
     *
     * DO NOT RENAME THESE PARAMETERS, as they are serialized (by Jenkins) in jobs/[PROJECT]/config.xml
     */
    @DataBoundConstructor
    public TicsAnalyzer(
            final String ticsPath
            , final String ticsConfiguration
            , final String projectName
            , final String branchName
            , final String branchDirectory
            , final String environmentVariables
            , final boolean createTmpdir
            , final String tmpdir
            , final String extraArguments
            , final Metrics calc
            , final Metrics recalc
            , boolean installTics
            , final String ticsEnvVariable
            //, final String credentialsId
            ) {
        this.ticsPath = ticsPath;
        this.ticsConfiguration = ticsConfiguration;
        this.projectName = projectName;
        this.branchName = branchName;
        this.branchDirectory = branchDirectory;
        this.environmentVariables = environmentVariables;
        this.createTmpdir = createTmpdir;
        this.tmpdir = tmpdir;
        this.extraArguments = extraArguments;
        this.calc = calc == null ? new Metrics() : calc;
        this.recalc = recalc == null ? new Metrics() : recalc;
        this.installTics = installTics;
        this.ticsEnvVariable = ticsEnvVariable;
        //this.credentialsId = credentialsId;
    }

    @Override
    public void perform(@Nonnull final Run<?, ?> run, @Nonnull final FilePath workspace, @Nonnull final Launcher launcher, @Nonnull final TaskListener listener) throws IOException, InterruptedException {
        final String errorPrefix = "TICS Analysis failed with exit code: ";
        final PrintStream logger = listener.getLogger();
        try {
            final EnvVars buildEnv = run.getEnvironment(listener);
            String installTicsApiFullUrl = "";
            int exitCode;

            if (installTics) {
                final String tiobeWebBaseUrl;
                final String ticsInstallApiBaseUrl;

                try {
                    tiobeWebBaseUrl = ValidationHelper.getTiobewebBaseUrlFromGivenUrl(ticsEnvVariable);
                    ticsInstallApiBaseUrl = getInstallTicsApiUrl(tiobeWebBaseUrl, getNodeOs(launcher));
                } catch (final InvalidTicsViewerUrl | URISyntaxException ex) {
                    ex.printStackTrace(listener.getLogger());
                    throw new IllegalArgumentException(LOGGING_PREFIX + "Invalid TICS Viewer URL", ex);
                }

//                final Optional<StandardUsernamePasswordCredentials> credentials = getStandardUsernameCredentials(run.getParent(), this.credentialsId);
                final InstallTicsApiCall installTicsApiCall = new InstallTicsApiCall(ticsInstallApiBaseUrl, Optional.empty(), listener);
                final String installTicsApiData = installTicsApiCall.retrieveInstallTics();
                installTicsApiFullUrl = tiobeWebBaseUrl + installTicsApiData;
            }

            exitCode = launchTicsQServer(installTicsApiFullUrl, run, launcher, listener, buildEnv, workspace);
            if (exitCode != 0) {
                logger.println(LOGGING_PREFIX + "Exit code " + exitCode);
                throw new RuntimeException(LOGGING_PREFIX + errorPrefix + exitCode);
            }
        } catch (final IOException e) {
            logger.println(LOGGING_PREFIX + e.getMessage());
            throw e;
        }
    }

    /** Prefixes given command with location of TICS, if available */
    private String getFullyQualifiedPath(final String command) {
        String path = MoreObjects.firstNonNull(ticsPath, "").trim();
        if ("".equals(path) || installTics) {
            return command;
        }
        // Note: we do not use new File(), because we do not want use the local FileSystem
        if (!path.endsWith("/") && !path.endsWith("\\")) {
            path += "/";
        }
        return path + command;
    }

    int launchTicsQServer(final String url, final Run run, final Launcher launcher, final TaskListener listener, final EnvVars buildEnv, final FilePath workspace) throws IOException, InterruptedException {

        final String bootstrapCommand =  installTics ? getBootstrapCmd(url, launcher) : "";
        final ArgumentListBuilder ticsAnalysisCommand = getTicsQServerArgs(buildEnv);

        final FilePath scriptPath = createScript(workspace, bootstrapCommand, ticsAnalysisCommand, launcher);
        final ProcStarter starter = launcher.new ProcStarter().stdout(listener).cmdAsSingleString(runScript(scriptPath.getRemote(), launcher)).envs(getEnvMap(buildEnv));

        final Proc proc = launcher.launch(starter);
        final int exitCode = proc.join();

        scriptPath.delete();

        return exitCode;
    }

    private ArgumentListBuilder getTicsQServerArgs(final EnvVars buildEnv) {
        final ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(getFullyQualifiedPath("TICSQServer"));

        if (isNotEmpty(projectName)) {
            args.add("-project");
            args.add(Util.replaceMacro(projectName, buildEnv));
        }
        if (isNotEmpty(branchName)) {
            args.add("-branchname");
            args.add(Util.replaceMacro(branchName, buildEnv));
        }

        if (!Strings.isNullOrEmpty(branchDirectory)) {
            args.add("-branchdir");
            args.add(Util.replaceMacro(branchDirectory, buildEnv));
        }

        if (createTmpdir && isNotEmpty(tmpdir)) {
            args.add("-tmpdir");
            args.add(Util.replaceMacro(tmpdir.trim(), buildEnv));
        }
        if (isNotEmpty(extraArguments)) {
            args.addTokenized(Util.replaceMacro(extraArguments.trim(), buildEnv));
        }
        addMetrics(args, "-calc", calc);
        addMetrics(args, "-recalc", recalc);

        return args;
    }

    private String getBootstrapCmd(final String url, final Launcher launcher) {
        final boolean isLinux = launcher.isUnix(); 
        if (isLinux) {
            return ". <(curl --silent --show-error \'" + url + "\' )";
        } else {
            return "powershell \"Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString('" + url + "'))\"";
        }
    }

    private String runScript(final String file, final Launcher launcher) {
        final boolean isLinux = launcher.isUnix();

        if (isLinux) {
            return "bash " + file;
        } else {
            return "powershell -NoProfile -ExecutionPolicy Bypass -Command \" & '" + file +  "'\"";
        }
    }

    private FilePath createScript(final FilePath workspace, final String bootstrapCmd, final ArgumentListBuilder ticsAnalysisCmd, final Launcher launcher) throws IOException, InterruptedException {
        final boolean isLinux = launcher.isUnix();

        final String scriptSuffix =  isLinux ? ".sh" : ".ps1";
        final String scriptContentStart = isLinux ? "#!/bin/bash" : "";
        final String ticsAnalysisCmdEscaped = isLinux 
                ? ticsAnalysisCmd.toList().stream().map(a -> StringEscapeUtils.escapeXSI(a)).collect(Collectors.joining(" ")) 
                : ticsAnalysisCmd.toWindowsCommand().toString();

        FilePath createTempFile = workspace.createTempFile("tics", scriptSuffix);

        String contents = scriptContentStart + " \n"
                + bootstrapCmd + "\n"
                + ticsAnalysisCmdEscaped;

        createTempFile.write(contents, "UTF-8");

        return createTempFile;
    }

    private String getInstallTicsApiUrl(final String tiobewebBaseUrl, final String os) throws URISyntaxException {
        final URIBuilder builder = new URIBuilder(ticsEnvVariable)
                .addParameter("platform", os)
                .addParameter("url", tiobewebBaseUrl);
        return builder.build().toString();
    }

    private String getNodeOs(final Launcher launcher) {
        return launcher.isUnix() ? "linux" : "windows";
    }

    private static boolean isNotEmpty(final String arg) {
        return !"".equals(Strings.nullToEmpty(arg).trim());
    }

    void addMetrics(final ArgumentListBuilder args, final String key, final Metrics metrics) {
        final ImmutableList<String> names = metrics.getEnabledMetrics();
        if (!names.isEmpty()) {
            args.add(key);
            args.add(Joiner.on(",").join(names));
        }
    }

    Map<String, String> getEnvMap(final EnvVars buildEnv) {
        final Map<String, String> out = Maps.newLinkedHashMap();
        final ImmutableList<String> lines = ImmutableList.copyOf(Splitter.onPattern("\r?\n").split(MoreObjects.firstNonNull(environmentVariables, "")));
        for (final String line : lines) {
            final ArrayList<String> splitted = Lists.newArrayList(Splitter.on("=").limit(2).split(line));
            if (splitted.size() == 2) {
                out.put(splitted.get(0).trim(), Util.replaceMacro(splitted.get(1).trim(), buildEnv));
            }
        }

        if (isNotEmpty(ticsConfiguration)) {
            out.put("TICS", Util.replaceMacro(ticsConfiguration, buildEnv));
        }
        return out;
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
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Run TICS";
        }

        @Override
        public boolean isApplicable(final Class type) {
            return true;
        }

        @Override
        public boolean configure(final StaplerRequest staplerRequest, final JSONObject json) throws FormException {
            save();
            return true; // indicate that everything is good so far
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

        @POST
        public FormValidation doCheckProjectName(@AncestorInPath final Item item, @QueryParameter final String value) {
            if (item == null) { // no context
                return FormValidation.ok();
            }
            item.checkPermission(Item.CONFIGURE);

            if ("".equals(Strings.nullToEmpty(value).trim())) {
                return FormValidation.error("Please provide a project name");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckBranchName(@AncestorInPath final Item item, @QueryParameter final String value) {
            if (item == null) { // no context
                return FormValidation.ok();
            }
            item.checkPermission(Item.CONFIGURE);

            if ("".equals(Strings.nullToEmpty(value).trim())) {
                return FormValidation.error("Please provide a branch name");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckTmpdir(@AncestorInPath final Item item, @QueryParameter final String value, @QueryParameter final boolean createTmpdir) {
            if (item == null) { // no context
                return FormValidation.ok();
            }
            item.checkPermission(Item.CONFIGURE);

            if (createTmpdir && "".equals(Strings.nullToEmpty(value).trim())) {
                return FormValidation.error("Please provide a directory");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckTicsEnvVariable(@AncestorInPath final Item item,  @AncestorInPath final TaskListener listener, @QueryParameter final String value) {
            if (item == null) { // no context
                return FormValidation.ok();
            }
            item.checkPermission(Item.CONFIGURE);

            Optional<FormValidation> validation = ValidationHelper.checkViewerUrlIsEmpty(value);
            if (validation.isPresent()) {
                return validation.get();
            }

            final Pattern urlPattern = Pattern.compile("[^:/]+://[^/]+/[^/]+/[^/]+/api/cfg\\?name=.+");
            final String urlErrorExample = "http(s)://hostname/tiobeweb/section/api/cfg?name=configurationΝame";
            validation = ValidationHelper.checkViewerUrlPattern(value, urlPattern, urlErrorExample);
            if (validation.isPresent()) {
                return validation.get();
            }

            validation = ValidationHelper.checkViewerBaseUrlAccessibility(value);
            if (validation.isPresent()) {
                return validation.get();
            }

            validation = ValidationHelper.checkVersionCompatibility(listener, value);
            if (validation.isPresent()) {
                return validation.get();
            }
            
            validation = ValidationHelper.checkViewerUrlForWarningsCommon(value);
            if (validation.isPresent()) {
                return validation.get();
            }

            return FormValidation.ok();
        }

    }
}
