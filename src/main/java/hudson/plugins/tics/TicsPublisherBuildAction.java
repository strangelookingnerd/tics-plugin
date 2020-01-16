package hudson.plugins.tics;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.http.client.utils.URIBuilder;
import org.joda.time.Instant;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;

import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Descriptor;
import hudson.model.ProminentProjectAction;
import hudson.model.Run;
import hudson.plugins.tics.TicsPublisher.InvalidTicsViewerUrl;
import hudson.plugins.tics.TqiPublisherResultBuilder.TqiPublisherResult;
import hudson.tasks.Publisher;
import hudson.util.DescribableList;
import jenkins.tasks.SimpleBuildStep;

/**
 * Note: the fields of TicsPublisherBuildAction are serialized in Jenkins' build.xml files,
 * including the build field!
 * DO NOT CHANGE THESE FIELDS names!
 * @author dreniers
 */
public class TicsPublisherBuildAction extends AbstractTicsPublisherAction implements ProminentProjectAction, SimpleBuildStep.LastBuildAction {
    /**
     * Build will be stored as a reference in build.xml as: '&lt;build class="build" reference="../../.."/&gt;'
     * I tried adding the publisher as a field here, but that just stores the fields, not the reference.
     **/
    private final Run<?, ?> run;
    public final String tableHtml;
    public final String ticsPath;
    public final String measurementDate;
    private final List<TicsPublisherProjectAction> projectActions;

    public TicsPublisherBuildAction(final Run<?, ?> run, final TqiPublisherResult result) {
        this.run = run;
        this.tableHtml = result.tableHtml;
        this.ticsPath = result.ticsPath;
        this.measurementDate = result.measurementDate;
        List<TicsPublisherProjectAction> projectActions = new ArrayList<>();
        projectActions.add(new TicsPublisherProjectAction(run));
        this.projectActions = projectActions;
    }

    public String getIconFileName() {
        // We return null to indicate that their should not be a link in the sidebar on the left.
        // The TICS results are already in the summary.
        return null;
    }

    public String getMeasurementDate() {
        if (Strings.isNullOrEmpty(measurementDate)) {
            return "?";
        }
        return new Instant(measurementDate).toDateTime().toString("yyyy-MM-dd HH:mm");
    }

    /**
     * From: http://grepcode.com/file_/repo1.maven.org/maven2/org.hudsonci.plugins/artifactory/2.1.3-h-2/org/jfrog/hudson/action/ActionableHelper.java/?v=source
     * @return The project publisher of the given type. Null if not found.
     */
    public static <T extends Publisher> T getPublisher(final Run<?, ?> run, final Class<T> type) {
        // Pipeline runs are not an instance of AbstractProject
        if (run.getParent() instanceof AbstractProject<?,?>) {
            final AbstractProject<?, ?> project = (AbstractProject<?, ?>) run.getParent();
            final DescribableList<Publisher, Descriptor<Publisher>> publishersList = project.getPublishersList();
            for (final Publisher publisher : publishersList) {
                if (type.isInstance(publisher)) {
                    return type.cast(publisher);
                }
            }
        }
        return null;
    }

    public final String getOpenInViewerUrl() {
        if (run == null) {
            return "";
        }

        final TicsPublisher publisher = getPublisher(run, TicsPublisher.class);
        if (publisher == null) {
            return "";
        }
        final String baseUrl;
        try {
            baseUrl = publisher.getResolvedTiobewebBaseUrl();
        } catch (final InvalidTicsViewerUrl e) {
            return "";
        }
        final URI uri;
        try {
            uri = new URIBuilder(baseUrl + "/TqiDashboard.html")
                .setFragment("axes=" + ticsPath)
                .build();
        } catch (final URISyntaxException e) {
            throw Throwables.propagate(e);
        }
        return uri.toString();
   }

    @Override
    public Collection<? extends Action> getProjectActions() {
        return this.projectActions;
    }
}
