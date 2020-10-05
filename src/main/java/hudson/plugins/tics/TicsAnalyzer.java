package hudson.plugins.tics;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

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
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
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

    /**
     * This annotation tells Hudson to call this constructor, with values from the configuration form page with matching parameter names.
     * See https://wiki.jenkins-ci.org/display/JENKINS/Basic+guide+to+Jelly+usage+in+Jenkins for explanation of DataBoundConstructor
     *
     * DO NOT RENAME THESE PARAMETERS, as they are serialized (by Jenkins) in jobs/[PROJECT]/config.xml
     */
    @DataBoundConstructor
    public TicsAnalyzer(
            final String ticsPath
            ,final String ticsConfiguration
            ,final String projectName
            ,final String branchName
            ,final String branchDirectory
            ,final String environmentVariables
            ,final boolean createTmpdir
            ,final String tmpdir
            ,final String extraArguments
            ,final Metrics calc
            ,final Metrics recalc
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
    }

    @Override
    public void perform(@Nonnull final Run<?, ?> run, @Nonnull final FilePath workspace, @Nonnull final Launcher launcher, @Nonnull final TaskListener listener) throws IOException, InterruptedException {
        final String errorPrefix = "TICS Analysis failed with exit code: ";
        final PrintStream logger = listener.getLogger();
        try {
            final EnvVars buildEnv = run.getEnvironment(listener);
            int exitCode;

            if (!Strings.isNullOrEmpty(branchDirectory)) {
                exitCode = setBranchDirUsingTicsMaintenance(run, launcher, listener, buildEnv);
                if (exitCode != 0) {
                    logger.println(LOGGING_PREFIX + "Exit code " + exitCode);
                    throw new RuntimeException(LOGGING_PREFIX + errorPrefix + exitCode);
                }
            }

            exitCode = launchTicsQServer(run, launcher, listener, buildEnv);
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
        String path = MoreObjects.firstNonNull(ticsPath,"").trim();
        if ("".equals(path)) {
            return command;
        }
        // Note: we do not use new File(), because we do not want use the local FileSystem
        if (!path.endsWith("/") && !path.endsWith("\\")) {
            path += "/";
        }
        return path + command;
    }

    private int setBranchDirUsingTicsMaintenance(final Run run, final Launcher launcher, final TaskListener listener, final EnvVars buildEnv) throws IOException, InterruptedException {
        final List<String> args = Lists.newArrayList();
        args.add(getFullyQualifiedPath("TICSMaintenance"));
        args.add("-project");
        args.add(Util.replaceMacro(projectName, buildEnv));
        args.add("-branchname");
        args.add(Util.replaceMacro(branchName, buildEnv));
        args.add("-branchdir");
        args.add(Util.replaceMacro(branchDirectory, buildEnv));
        final ProcStarter starter = launcher.new ProcStarter().stdout(listener).cmds(args).envs(getEnvMap(buildEnv));
        final Proc proc = launcher.launch(starter);
        final int exitCode = proc.join();
        return exitCode;
    }

    int launchTicsQServer(final Run run, final Launcher launcher, final TaskListener listener, final EnvVars buildEnv) throws IOException, InterruptedException {
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
        if (createTmpdir && isNotEmpty(tmpdir)) {
            args.add("-tmpdir");
            args.add(Util.replaceMacro(tmpdir.trim(), buildEnv));
        }
        if (isNotEmpty(extraArguments)) {
            args.addTokenized(Util.replaceMacro(extraArguments.trim(), buildEnv));
        }
        addMetrics(args, "-calc", calc);
        addMetrics(args, "-recalc", recalc);
        final ProcStarter starter = launcher.new ProcStarter().stdout(listener).cmds(args).envs(getEnvMap(buildEnv));
        final Proc proc = launcher.launch(starter);
        final int exitCode = proc.join();
        return exitCode;
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

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
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

    }
}
