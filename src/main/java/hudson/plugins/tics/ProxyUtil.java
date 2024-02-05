package hudson.plugins.tics;

import java.io.PrintStream;
import java.net.ProxySelector;
import java.util.List;
import java.util.regex.Pattern;

import com.google.common.base.Strings;

import hudson.ProxyConfiguration;
import hudson.util.Secret;
import jenkins.model.Jenkins;

public class ProxyUtil {
    private final PrintStream logger;

    public ProxyUtil(final PrintStream logger) {
        this.logger = logger;
    }

    public void setDefaultProxyFromJenkinsConfiguration() {
        final Jenkins jenkins = Jenkins.get();

        if (jenkins != null) {
            final ProxyConfiguration proxy = jenkins.proxy;
            if (proxy != null) {
                final String proxyName = proxy.getName();
//                final String proxyPort = Integer.toString(proxy.getPort());
                final int proxyPort = proxy.getPort();
                final String noProxyHosts = proxy.getNoProxyHost().replace("\n", "|");
                final List<Pattern> noProxyPatterns = proxy.getNoProxyHostPatterns();
                final String proxyUser = proxy.getUserName();
                final String proxyPass = Secret.toString(proxy.getSecretPassword());

                logger.println("Found http(s) proxy setting: " + proxyName + ":" + proxyPort);
                if (!Strings.isNullOrEmpty(noProxyHosts)) {
                    logger.println("Found no proxy setting: " + noProxyHosts);
                }
                final ProxySelector jenkinsProxySelector = new JenkinsProxySelector(noProxyPatterns, proxyName, proxyPort, proxyUser, proxyPass, logger);
                ProxySelector.setDefault(jenkinsProxySelector);
            }
        }
    }

    public void restoreDefaultProxySelector(final ProxySelector defProxySelector) {
        if (ProxySelector.getDefault().getClass() == JenkinsProxySelector.class) {
            ProxySelector.setDefault(defProxySelector);
        }
    }
}
