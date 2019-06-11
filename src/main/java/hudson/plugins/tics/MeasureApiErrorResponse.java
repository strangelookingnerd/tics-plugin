package hudson.plugins.tics;

import java.util.List;

import com.google.common.collect.Lists;

public class MeasureApiErrorResponse {
    public static class AlertMessage {
        public String message;
        public String stackTrace;
        public String subMessage;
    }
    public List<AlertMessage> alertMessages = Lists.newArrayList();
}
