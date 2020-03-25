package hudson.plugins.tics;

import java.net.ConnectException;
import java.util.Optional;

import javax.annotation.Nonnull;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;


public class TicsQualityGate extends AbstractApiCall {

    public static class QualityGateResult {
        public String tableHtml;
        public String viewerGateUrl;

        public QualityGateResult(final String html, final String viewerGateUrl) {
            this.tableHtml = html;
            this.viewerGateUrl = viewerGateUrl;
        }

    }

    public QualityGateResult createQualityGateResult() throws Exception {
        retrieveQualityGateStatus();
        final String tableHtml = createQualityGateHtml();
        return new QualityGateResult(tableHtml, viewerGateUrl);
    }

    private static final String LOGGING_PREFIX = "[TICS Quality Gating] ";
    private static final String GREEN_FLAG = "/plugin/tics/greenFlag.png";
    private static final String RED_FLAG = "/plugin/tics/redFlag.png";
    public final String qualityGateUrl;
    public final boolean failIfQualityGateFails;
    public final Optional<StandardUsernamePasswordCredentials> credentials;
    public final Run<?, ?> run;
    public final TaskListener listener;
    public final String project;
    public final String branch;
    public final String tiobeWebBaseUrl;
    public JsonObject jsonObject;
    public String viewerGateUrl;

    public TicsQualityGate(final String qualityGateUrl, final String tiobeWebBaseUrl, final boolean failIfQualityGateFails, final String ticsPath, final Optional<StandardUsernamePasswordCredentials> credentials, @Nonnull final Run<?, ?> run, final TaskListener listener) {
        super(LOGGING_PREFIX, listener.getLogger(), credentials);
        final String projectAndBranch = ticsPath.split("://")[1];
        this.project = projectAndBranch.split("/")[0];
        this.branch = projectAndBranch.split("/")[1];
        this.qualityGateUrl = qualityGateUrl;
        this.tiobeWebBaseUrl = tiobeWebBaseUrl;
        this.failIfQualityGateFails = failIfQualityGateFails;
        this.credentials = credentials;
        this.run = run;
        this.listener = listener;
    }

    public void retrieveQualityGateStatus() throws Exception {
        final String qualityGateUrl1 = qualityGateUrl + "?project=" + project + "&branch=" + branch;
        final String body = getHttpRequest(qualityGateUrl1);

        parseQualityGateJson(body);
    }

    private String getHttpRequest(final String url) throws Exception {
        final String body;
        final HttpGet httpGet = new HttpGet(url);
        try (final CloseableHttpClient httpclient = createHttpClient();
             final CloseableHttpResponse response = httpclient.execute(httpGet);
        ) {
            body = EntityUtils.toString(response.getEntity());
            throwIfStatusNotOk(response, body);
        } catch (final ConnectException e) {
            throw new ConnectException(e.getMessage());
        } catch (final Exception e) {
            throw new Exception(e.toString() /* Includes exception name for more information*/);
        }
        return body;
    }

    private void parseQualityGateJson(final String body) {
        try {
            final JsonElement jsonElement = new JsonParser().parse(body);
            this.jsonObject = jsonElement.getAsJsonObject();
        } catch (final JsonSyntaxException ex) {
            throw new RuntimeException(LOGGING_PREFIX + "Error parsing json: " + body, ex);
        }
    }

    private String createQualityGateHtml() {
        this.viewerGateUrl = jsonObject.get("url").getAsString();
        final String qualityGateViewerUrl = tiobeWebBaseUrl + "/" + this.viewerGateUrl;
        final String escapedQualityGateViewerUrl = qualityGateViewerUrl.replace("(", "%28").replace(")", "%29");
        final boolean passed = jsonObject.get("passed").getAsBoolean();
        final String qualityGateStatus = passed ? "passed" : "failed";
        if (!passed && failIfQualityGateFails) {
            run.setResult(Result.FAILURE);
        }
        listener.getLogger().println(LOGGING_PREFIX + " Quality Gate " + qualityGateStatus
                + ". Please check the following url for more information: " + escapedQualityGateViewerUrl);
        final StringBuilder sb = new StringBuilder();
        sb.append("<p>");
        sb.append("<b>Project:</b> ");
        sb.append(project).append("/").append(branch);
        sb.append("</p>");
        sb.append("<p>");
        sb.append(jsonObject.get("message").getAsString());
        sb.append("</p>");
        final JsonArray gates = jsonObject.getAsJsonArray("gates");
        sb.append(qualityGateSummary(gates));
        return sb.toString();
    }

    private String qualityGateSummary(final JsonArray gates) {
        final String greenFlagElement = "<img src='" + GREEN_FLAG + "' width='30' height='20'>";
        final String redFlagElement = "<img src='" + RED_FLAG + "' width='30' height='20'>";

        final StringBuilder sb = new StringBuilder();
        for (final JsonElement gate : gates) {
            final String gateName = gate.getAsJsonObject().get("name").getAsString();
            final JsonArray gateConditions = gate.getAsJsonObject().getAsJsonArray("conditions");
            int failedConditionsNr = 0;
            int successfulConditionsNr = 0;
            for (final JsonElement condition : gateConditions) {
                if (condition.getAsJsonObject().get("passed").getAsBoolean()) {
                    successfulConditionsNr++;
                } else {
                    failedConditionsNr++;
                }
            }

            final HtmlTag div = HtmlTag.from("div")
                    .attr("style", "margin-top: 15px");
            sb.append(div.open());
            sb.append("<div style='float: right; display: inline;'>")
                    .append(redFlagElement)
                    .append(" ")
                    .append(failedConditionsNr)
                    .append(" failed ")
                    .append("<img style='margin-left: 5px' src='" + GREEN_FLAG + "' width='30' height='20'>")
                    .append(" ")
                    .append(successfulConditionsNr)
                    .append(" passed")
                    .append("</div>");
            sb.append("<h4 style='margin-bottom: 6px'>").append(gateName).append("</h4>");
            sb.append(div.close());
            final HtmlTag table = HtmlTag.from("table")
                    .attr("id", "quality-gate")
                    .attr("style", "border-spacing: 0px")
                    .attr("style", "border-collapse: collapse")
                    .attr("style", "margin-bottom: 20px");

            sb.append(table.open());
            sb.append("<thead>");
            sb.append("<tr>");
            sb.append("</tr>");
            sb.append("</thead>");
            sb.append("<colgroup><col><col style='width: 100%'><col></colgroup>");
            sb.append("<tbody>");

            for (final JsonElement condition : gateConditions) {
                final boolean didConditionPass = condition.getAsJsonObject().get("passed").getAsBoolean();
                final String conditionFlag = didConditionPass ? greenFlagElement : redFlagElement;
                final String conditionMessage = condition.getAsJsonObject().get("message").getAsString();
                final HtmlTag tr = HtmlTag.from("tr")
                        .attr("style", "border-top: 1px solid #CCC");
                sb.append(tr.open());

                HtmlTag td = HtmlTag.from("td")
                        .attr("style", "padding-top: 2px")
                        .attr("style", "background-color: #FFF")
                        .attr("style", "padding: 4px 5px ");
                sb.append(td.open());
                sb.append(conditionFlag);
                sb.append(td.close());

                td = td.attr("style", "text-align: left");
                sb.append(td.open());
                sb.append(conditionMessage);
                sb.append(td.close());
            }

            sb.append("</tbody>");
            sb.append("</table>");
        }
        return sb.toString();
    }
}
