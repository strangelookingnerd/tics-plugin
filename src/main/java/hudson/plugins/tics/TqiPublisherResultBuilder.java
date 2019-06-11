package hudson.plugins.tics;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import hudson.plugins.tics.MeasureApiCall.MeasureApiCallException;
import hudson.plugins.tics.MeasureApiSuccessResponse.Baseline;
import hudson.plugins.tics.MeasureApiSuccessResponse.Metric;
import hudson.plugins.tics.MeasureApiSuccessResponse.MetricValue;
import hudson.plugins.tics.MeasureApiSuccessResponse.Run;
import hudson.plugins.tics.MeasureApiSuccessResponse.TqiVersion;

public class TqiPublisherResultBuilder {
    public static class TqiPublisherResult {
        public String tableHtml;
        public String ticsPath;
        public String measurementDate = Instant.now().toString();

        public TqiPublisherResult(final String html, final String ticsPath) {
            this.tableHtml = html;
            this.ticsPath = ticsPath;
        }
    }

    public static final String METRICS_3_11 = "tqi,tqiTestCoverage,tqiAbstrInt,tqiComplexity,tqiCompWarn,tqiCodingStd,tqiDupCode,tqiFanOut,tqiDeadCode,loc";
    public static final String METRICS_4_0 = "tqi,tqiTestCoverage,tqiAbstrInt,tqiComplexity,tqiCompWarn,tqiCodingStd,tqiDupCode,tqiFanOut,tqiSecurity,loc";
    public static final String TQI_VERSION = "tqiVersion";
    private final PrintStream logger;
    private final DecimalFormat percentageFormatter;
    private final NumberFormat integerFormatter;
    private MeasureApiCall measureApiCall;
    private final Optional<StandardUsernamePasswordCredentials> credentials;
    private final String ticsPath;
    private final String measureApiUrl;

    public TqiPublisherResultBuilder(final PrintStream logger,
            final Optional<StandardUsernamePasswordCredentials> credentials, final String measureApiUrl, final String ticsPath) {
        this.logger = logger;
        this.credentials = credentials;
        this.measureApiUrl = measureApiUrl;
        this.ticsPath = ticsPath;
        this.percentageFormatter = ((DecimalFormat)NumberFormat.getInstance(Locale.US));
        this.percentageFormatter.setMaximumFractionDigits(2);
        this.percentageFormatter.setMinimumFractionDigits(2);
        this.percentageFormatter.setRoundingMode(RoundingMode.FLOOR);
        this.integerFormatter = NumberFormat.getInstance(Locale.US);
    }


    public void close() {
        if(measureApiCall != null) {
            measureApiCall.close();
        }
    }

    public TqiPublisherResult run() {
        String tableHtml;
        try {
            this.measureApiCall = new MeasureApiCall(logger, measureApiUrl, credentials);
            tableHtml = createTableHtml();
        } catch(final Exception ex) {
            ex.printStackTrace(logger);
            final String msg = ex.getMessage();
            tableHtml = "<div>"
                     + "An error occurred while retrieving metrics: "
                    + "<blockquote>" + msg + "</blockquote>"
                    + "Consult the console output for more information."
                    + "</div>";

        }
        return new TqiPublisherResult(tableHtml, ticsPath);
    }


    private String createTableHtml() throws MeasureApiCallException {
        final boolean hasSecurity = doesTqiVersionIncludeSecurity();
        final String metrics = hasSecurity ? METRICS_4_0 : METRICS_3_11;
        final MeasureApiSuccessResponse<Double> mvsCurrent = measureApiCall.execute(MeasureApiCall.RESPONSE_DOUBLE_TYPETOKEN, ticsPath, metrics);
        final Optional<MeasureApiSuccessResponse<Double>> mvsPrevious = getPreviousRunDate().isPresent() ? tryQueryMetricsForDate(getPreviousRunDate().get(), metrics) : Optional.<MeasureApiSuccessResponse<Double>>absent();
        final Optional<MeasureApiSuccessResponse<Double>> mvsBaseline = baseline.get().isPresent() ? tryQueryMetricsForDate(baseline.get().get().getStarted(), metrics) : Optional.<MeasureApiSuccessResponse<Double>>absent();

        final StringBuilder sb = new StringBuilder();
        final HtmlTag table = HtmlTag.from("table")
                .attr("style", "border-spacing: 0px")
                .attr("style", "border: 1px solid #CCC");
        sb.append(table.open());
        sb.append("<thead>");
        sb.append("<tr>");
        sb.append(HtmlTag.from("th").attr("style", "text-align: left").openClose("Metric"));
        HtmlTag th = HtmlTag.from("th")
                .attr("style", "text-align: right")
                .attr("style", "width: 80px");
        th = th.attr("style", "cursor: help");
        sb.append(th.attr("title", "Last TICS run was at\n" + getLastRunDate().or(Instant.now()).toDateTime().toString(DateTimeFormat.longDateTime())).openClose("Current"));
        sb.append("<th style=\"width: 26px\"><!-- Letter --></th>");
        if(mvsPrevious.isPresent()) {
            final String title = "Delta with previous TICS run at\n" + getPreviousRunDate().or(Instant.now()).toDateTime().toString(DateTimeFormat.longDateTime());
            sb.append(th.attr("title", title).openClose("&Delta;Previous"));
        }
        if(mvsBaseline.isPresent() && baseline.get().isPresent()) {
            final Baseline bl = baseline.get().get();
            final String title = "Delta with baseline '" + bl.getName() + "' at\n" + bl.getStarted().toDateTime().toString(DateTimeFormat.longDateTime());
            sb.append(th.attr("title", title).open());
            final HtmlTag div = HtmlTag.from("div")
                    .attr("style", "overflow: hidden")
                    .attr("style", "text-overflow: clip")
                    .attr("style", "white-space: nowrap")
                    .attr("style", "width: 80px")
                    ;
            sb.append(div.open());
            sb.append("&Delta;" + bl.getName());
            sb.append(div.close());
        }
        sb.append("</tr>");
        sb.append("</thead>");
        sb.append("<tbody>");
        final MetricValue<Double> EMPTY_METRICVALUE = new MetricValue<Double>();
        for(int i=0; i<mvsCurrent.data.size(); i++) {
            final Metric metric = mvsCurrent.metrics.get(i);
            final MetricValue<Double> mvNow = Iterables.get(mvsCurrent.data, i, EMPTY_METRICVALUE);
            final HtmlTag tr = HtmlTag.from("tr")
                    ;
            sb.append(tr.open());

            HtmlTag td = HtmlTag.from("td")
                    .attr("style", "padding-top: 2px; padding-bottom: 2px;")
                    .attr("style", "background-color: " + (i % 2 == 0 ? "#EEE" : "#FFF"))
                    ;
            sb.append(td.open());
            sb.append(metric.fullName);
            sb.append(td.close());

            // Output current value
            td = td.attr("style", "text-align: right");
            sb.append(td.open());
            if(mvNow.value == null) {
                sb.append("-");
            } else {
                if(isPercentageMetric(metric)) {
                    sb.append(percentageFormatter.format(mvNow.value));
                    sb.append("%");
                } else {
                    sb.append(integerFormatter.format(mvNow.value));
                }
            }
            sb.append(td.close());


            // Output letter
            sb.append(td.open());
            sb.append(getLetterToBadge(mvNow.letter));
            sb.append(td.close());

            if(mvsPrevious.isPresent()) {
                outputDeltaCell(sb, td, metric, mvNow.value, Iterables.get(mvsPrevious.get().data, i, EMPTY_METRICVALUE).value);
            }

            if(mvsBaseline.isPresent()) {
                outputDeltaCell(sb, td, metric, mvNow.value, Iterables.get(mvsBaseline.get().data, i, EMPTY_METRICVALUE).value);
            }

            sb.append(tr.close());
        }
        sb.append("</tbody>");
        sb.append("</table>");
        return sb.toString();
    }

    private boolean doesTqiVersionIncludeSecurity() {
        MeasureApiSuccessResponse<TqiVersion> resp;
        try {
            resp = measureApiCall.execute(MeasureApiCall.RESPONSE_TQIVERSION_TYPETOKEN, ticsPath, TQI_VERSION);
        } catch (final MeasureApiCallException e) {
            e.printStackTrace(logger);
            return false;
        }
        if (resp.data.isEmpty() || resp.data.get(0).value == null) {
            return false;
        }
        final TqiVersion tqiVersion = resp.data.get(0).value;
        return tqiVersion.compareTo(new TqiVersion(4, 0)) >= 0;
    }


    private String getLetterToBadge(final String letter) {
        if(Strings.isNullOrEmpty(letter)) {
            return "";
        }
        final ImmutableMap<String, String> tqiBgColors = ImmutableMap.<String, String>builder()
                .put("A", "#006400")
                .put("B", "#64AE00")
                .put("C", "#FFFF00")
                .put("D", "#FF950E")
                .put("E", "#FF420E")
                .put("F", "#BE0000")
                .build();
        final ImmutableMap<String, String> tqiFgColors = ImmutableMap.<String, String>builder()
                .put("A", "white")
                .put("B", "white")
                .put("C", "black")
                .put("D", "black")
                .put("E", "white")
                .put("F", "white")
                .build();

        final String bgColor = tqiBgColors.get(letter);
        final String fgColor = tqiFgColors.get(letter);
        if(bgColor == null || fgColor == null) {
            return "";
        }

        return HtmlTag.from("span")
                .attr("style", "color: " + fgColor)
                .attr("style", "background-color: " + bgColor)
                .attr("style", "padding: 0 7px 0 7px")
                .attr("style", "border-radius: 5px")
                .attr("style", "font-weight: bold")
                .attr("style", "text-align: center")
                .attr("style", "box-shadow: 0px 0px 3px #888888")
                .openClose(letter);
    }


    private void outputDeltaCell(final StringBuilder sb, final HtmlTag td0, final Metric metric, final Double now, final Double prev) {
        if(now == null || prev == null) {
            sb.append("-");
            return;
        }
        BigDecimal delta;
        final String deltaAsText;
        if(isPercentageMetric(metric)) {
            final double diff = now.doubleValue() - prev.doubleValue();
            delta = new BigDecimal(diff).setScale(2, RoundingMode.UP);
            deltaAsText = delta.toPlainString();
        } else {
            final int diff = now.intValue() - prev.intValue();
            delta = new BigDecimal(diff).setScale(0);
            deltaAsText = integerFormatter.format(diff);
        }

        HtmlTag td = td0.attr("style", "text-align: right");
        if(metric.getExpression().startsWith("tqi") && delta.signum() != 0) {
            td = td.attr("style", "color: " + (delta.signum() > 0 ? "green" : "red"));
        }

        sb.append(td.open());
        if(delta.signum() > 0) {
            sb.append("+");
        }
        if(delta.signum() == 0) {
            sb.append("<span style=\"color: #BBB\">0.00</span>");
        } else {
            sb.append(deltaAsText);
        }
        sb.append(td.close());
    }

    private boolean isPercentageMetric(final Metric metric) {
        final boolean isPercentageMetric = metric.getExpression().startsWith("tqi");
        return isPercentageMetric;
    }

    private Optional<MeasureApiSuccessResponse<Double>> tryQueryMetricsForDate(final Instant date, final String metrics) {
        try {
            return Optional.of(measureApiCall.execute(MeasureApiCall.RESPONSE_DOUBLE_TYPETOKEN, ticsPath, metrics, Optional.of(date)));
        } catch (final MeasureApiCallException e) {
            e.printStackTrace(logger);
            return Optional.absent();
        }
    }


    private final Supplier<Optional<List<Run>>> runDates = Suppliers.memoize(new Supplier<Optional<List<Run>>>() {
        public Optional<List<Run>> get() {
            MeasureApiSuccessResponse<List<Run>> resp;
            try {
                resp = measureApiCall.execute(MeasureApiCall.RESPONSE_RUNS_TYPETOKEN, ticsPath, "runs", Optional.<Instant>absent());
            } catch (final MeasureApiCallException e) {
                e.printStackTrace(logger);
                return Optional.absent();
            }
            if(resp.data.isEmpty()) {
                return Optional.absent();
            }
            final MetricValue<List<Run>> mv = resp.data.get(0);
            return Optional.of(mv.value);
        }
    });

    private final Optional<Instant> getLastRunDate() {
        final List<Run> runs = runDates.get().or(ImmutableList.<Run>of());
        if(runs.isEmpty()) {
            return Optional.absent();
        }

        final Run run = runs.get(runs.size()-1);
        return Optional.of(run.getStarted());
    }

    private final Optional<Instant> getPreviousRunDate() {
        final List<Run> runs = runDates.get().or(ImmutableList.<Run>of());
        if(runs.size() <= 1) {
            return Optional.absent();
        }
        final Run run = runs.get(runs.size()-2);
        return Optional.of(run.getStarted());
    }

    private final Supplier<Optional<Baseline>> baseline = Suppliers.memoize(new Supplier<Optional<Baseline>>() {
        public Optional<Baseline> get() {
            MeasureApiSuccessResponse<List<Baseline>> resp;
            try {
                resp = measureApiCall.execute(MeasureApiCall.RESPONSE_BASELINES_TYPETOKEN, ticsPath, "baselines", Optional.<Instant>absent());
            } catch (final MeasureApiCallException e) {
                e.printStackTrace(logger);
                return Optional.absent();
            }
            if(resp.data.isEmpty()) {
                return Optional.absent();
            }
            final MetricValue<List<Baseline>> mv = resp.data.get(0);
            final List<Baseline> baselines = mv.value;
            if(baselines.isEmpty()) {
                return Optional.absent();
            }
            return Optional.of(baselines.get(baselines.size()-1));
        }
    });


}
