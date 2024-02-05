package hudson.plugins.tics;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

public class JenkinsProxyAuthenticator extends Authenticator {
    private final String proxyUser;
    private final String proxyPassword;


    public JenkinsProxyAuthenticator(final String proxyUser, final String proxyPassword) {
        super();
        this.proxyUser = proxyUser;
        this.proxyPassword = proxyPassword;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        // TODO Auto-generated method stub
        return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray());
    }

    public PasswordAuthentication getProxyCredentials() {
        return getPasswordAuthentication();
    }

}
