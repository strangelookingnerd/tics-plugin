package hudson.plugins.tics;

import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;

public class TicsPublisherProjectAction extends AbstractTicsPublisherAction {
    public final AbstractProject<?, ?> project;

    public TicsPublisherProjectAction(final AbstractProject<?, ?> project) {
        this.project = project;
    }

    public String getIconFileName() {
        // We return null to indicate that their should not be a link in the sidebar on the left.
        // The TICS results are already in the floating box.
        return null; 
        //return "/plugin/tics/tics24x24.gif";
    }


    /**
     * Gets the most recent {@link TicsPublisherBuildAction} object.
     */
    public TicsPublisherBuildAction getLastBuild() {
        //System.out.println("getLastResult()");
        for (AbstractBuild<?, ?> b = project.getLastBuild(); b != null; b = b.getPreviousBuild()) {
            if (b.isBuilding() || b.getResult() == Result.FAILURE || b.getResult() == Result.ABORTED) {
                continue;
            }
            final TicsPublisherBuildAction r = b.getAction(TicsPublisherBuildAction.class);
            if (r != null) {
                return r;
            }
        }
        return null;
    }
}
