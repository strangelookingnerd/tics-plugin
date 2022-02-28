package hudson.plugins.tics;

public class InstallTicsApiResponse {
    public static class Links {
        public String setPropPath;
        public String queryArtifact;
        public String uploadArtifact;
        /**
         * Link to GET API that will return a script that can be used to install TICS.
         * For this link to be returned you have to provide parameters:
         * <ul>
         * <li>configuration
         * <li>platform
         * <li>URL
         * </ul>
         **/
        public String installTics;
    }

    public final Links links = new Links();
}

