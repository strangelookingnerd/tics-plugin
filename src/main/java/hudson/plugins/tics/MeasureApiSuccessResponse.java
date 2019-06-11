package hudson.plugins.tics;

import java.util.List;

import org.joda.time.Instant;

import com.google.common.base.Objects;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;

/** Models part of the api/public/v1/Measure response that is needed for our Jenkins plugin */
public class MeasureApiSuccessResponse<T> {
    public static class MetricValue<T> {
        public T value;
        public String letter;
        public String formattedValue;
    }

    public static class Metric {
        public String expression;
        public String fullName;

        public String getExpression() {
            return Objects.firstNonNull(expression, "");
        }
    }

    public static class TqiVersion implements Comparable<TqiVersion> {
        public int major;
        public int minor;

        public TqiVersion(final int major, final int minor) {
            this.major = major;
            this.minor = minor;
        }

        public TqiVersion() { /* for gson */ }

        public int compareTo(final TqiVersion that) {
            return ComparisonChain.start()
                .compare(this.major, that.major)
                .compare(this.minor, that.minor)
                .result();
        }
    }


    public static class Run {
        public String started;

        public Instant getStarted() {
            return Instant.parse(started);
        }
    }

    public static class Baseline {
        public String name;
        public String instant;

        public Instant getStarted() {
            return Instant.parse(instant);
        }

        public String getName() {
            return Objects.firstNonNull(name, "?");
        }
    }

    public List<MetricValue<T>> data = Lists.newArrayList();
    public List<Metric> metrics = Lists.newArrayList();
}
