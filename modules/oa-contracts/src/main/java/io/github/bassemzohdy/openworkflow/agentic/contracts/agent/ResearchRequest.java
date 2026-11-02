package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

/** Canonical research request text used for immutable request binding. */
public final class ResearchRequest {
    public static final int MAX_TOPIC_LENGTH = 4096;
    private ResearchRequest() { }

    public static String canonical(String topic) {
        StringBuilder out = new StringBuilder(topic.length() + 12).append("{\"topic\":\"");
        for (int i = 0; i < topic.length(); i++) {
            char c = topic.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c) && i + 1 < topic.length()
                            && Character.isLowSurrogate(topic.charAt(i + 1))) {
                        out.append(c).append(topic.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        throw new IllegalArgumentException("the topic is not well-formed text");
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append("\"}").toString();
    }

}
