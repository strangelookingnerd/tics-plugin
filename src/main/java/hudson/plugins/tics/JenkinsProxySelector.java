package hudson.plugins.tics;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JenkinsProxySelector extends ProxySelector {
//    private final String noProxy;
    private final List<Pattern> noProxyPatterns;
    private final String proxyName;
    private final int proxyPort;
    private final String proxyUser;
    private final String proxyPassword;
    private final PrintStream logger;


    public JenkinsProxySelector(final List<Pattern> noProxyPatterns, final String proxyName, final int proxyPort, final String proxyUser, final String proxyPassword, final PrintStream logger) {
        super();
//        this.noProxy = noProxy;
        this.noProxyPatterns = noProxyPatterns;
        this.proxyName = proxyName;
        this.proxyPort = proxyPort;
        this.proxyUser = proxyUser;
        this.proxyPassword = proxyPassword;
        this.logger = logger;
    }

    @Override
    public List<Proxy> select(final URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI can't be null.");
        }
        return Collections.singletonList(isProxyExempted(uri.getHost()) ? Proxy.NO_PROXY : new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyName, proxyPort)));
    }

    protected boolean isProxyExempted(final String host) {
        Matcher matcher;
        for (final Pattern p : noProxyPatterns) {
            matcher = p.matcher(host);
            if(matcher.find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void connectFailed(final URI uri, final SocketAddress sa, final IOException ioe) {
        logger.println("Connect to " + uri + " failed because of exception: " + ioe);
    }

    public String getProxyName() {
        return this.proxyName;
    }

    public int getProxyPort() {
        return this.proxyPort;
    }

    public String getProxyUser() {
        return this.proxyUser;
    }

    public String getProxyPassword() {
        return this.proxyPassword;
    }
}
