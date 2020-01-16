package hudson.plugins.tics;

import hudson.model.Run;

public class TicsPublisherProjectAction extends AbstractTicsPublisherAction {
    public final Run<?, ?> run;

    public TicsPublisherProjectAction(final Run<?, ?> run) {
        this.run = run;
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
        for (Run<?, ?> b = run.getParent().getLastBuild(); b != null; b = b.getPreviousBuild()) {
            final TicsPublisherBuildAction r = b.getAction(TicsPublisherBuildAction.class);
            if (r != null) {
                return r;
            }
        }
        return null;
    }
}
