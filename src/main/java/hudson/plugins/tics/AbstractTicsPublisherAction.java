package hudson.plugins.tics;

import hudson.model.Action;

public abstract class AbstractTicsPublisherAction implements Action {
    public String getDisplayName() {
        return "TICS Results";
    }
    
    public String getUrlName() {
        return "tics";
    }
    
    public boolean isFloatingBoxActive() {
        return true;
    }
    
    
}
