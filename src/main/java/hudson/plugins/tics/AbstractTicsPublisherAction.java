package hudson.plugins.tics;

import hudson.model.Action;

public abstract class AbstractTicsPublisherAction implements Action {
    @Override
    public String getDisplayName() {
        return "TICS Results";
    }

    @Override
    public String getUrlName() {
        return "tics";
    }

    public boolean isFloatingBoxActive() {
        return true;
    }


}
