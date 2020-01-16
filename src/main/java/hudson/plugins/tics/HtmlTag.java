package hudson.plugins.tics;

import java.util.Collection;
import java.util.Map.Entry;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;

public class HtmlTag {
    private final String tag;
    private final ArrayListMultimap<String, String> attrs;

    private HtmlTag(final String tag, final ArrayListMultimap<String, String> attrs) {
        this.tag = tag;
        this.attrs = attrs;
    }

    public static HtmlTag from(final String tag) {
        return new HtmlTag(tag, ArrayListMultimap.<String, String>create());
    }

    public HtmlTag attr(final String name, final String value) {
        return attr(name, ImmutableList.of(value));
    }

    public HtmlTag attr(final String name, final Iterable<String> values) {
        final ArrayListMultimap<String, String> copy = ArrayListMultimap.create(attrs);
        copy.putAll(name, values);
        return new HtmlTag(tag, copy);
    }

    public HtmlTag attrIf(final boolean state, final String key, final String value) {
        if (state) {
            return attr(key, value);
        } else {
            return this;
        }
    }

    public String open() {
        final StringBuilder sb = new StringBuilder();
        sb.append("<");
        sb.append(this.tag);
        if (attrs != null) {
            for (final Entry<String, Collection<String>> entry : attrs.asMap().entrySet()) {
                sb.append(" ");
                final String valueSeparator;
                if ("style".equals(entry.getKey())) {
                    valueSeparator = "; ";
                } else if ("data-bind".equals(entry.getKey())) {
                    valueSeparator = ", ";
                } else {
                    valueSeparator = " ";
                }
                sb.append(entry.getKey() + "=\"" + Joiner.on(valueSeparator).join(entry.getValue()) + "\"");
            }
        }
        sb.append(">");
        return sb.toString();
    }
    public String close() {
        return "</" + tag + ">";
    }

    public String openClose() {
        return open() + close();
    }

    public String openClose(final String inner) {
        return open() + inner + close();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("tag", tag).toString();
    }

}
