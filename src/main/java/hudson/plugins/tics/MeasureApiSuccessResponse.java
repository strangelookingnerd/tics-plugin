package hudson.plugins.tics;

import java.util.List;

import org.joda.time.Instant;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** Models part of the api/public/v1/Measure response that is needed for our Jenkins plugin */
@SuppressFBWarnings({"UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD", "UUF_UNUSED_PUBLIC_OR_PROTECTED_FIELD"})
public class MeasureApiSuccessResponse<T> {
    public static class MetricValue<T> {
        public T value;
        public String status;
        public String letter;
        public String formattedValue;
    }

    public static class Metric {
        public String expression;
        public String fullName;

        public String getExpression() {
            return MoreObjects.firstNonNull(expression, "");
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

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.major;
            result = prime * result + this.minor;
            return result;
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TqiVersion other = (TqiVersion) obj;
            if (this.major != other.major)
                return false;
            return this.minor == other.minor;
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
            return MoreObjects.firstNonNull(name, "?");
        }
    }

    public List<MetricValue<T>> data = Lists.newArrayList();
    public List<Metric> metrics = Lists.newArrayList();
}
