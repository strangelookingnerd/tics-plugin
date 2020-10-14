package hudson.plugins.tics;

import java.util.List;

import com.google.common.collect.Lists;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings({"UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD", "UUF_UNUSED_PUBLIC_OR_PROTECTED_FIELD"})
public class MeasureApiErrorResponse {
    public static class AlertMessage {
        public String message;
        public String stackTrace;
        public String subMessage;
    }
    public List<AlertMessage> alertMessages = Lists.newArrayList();
}
