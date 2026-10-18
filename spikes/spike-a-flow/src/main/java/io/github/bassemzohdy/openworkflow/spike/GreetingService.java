package io.github.bassemzohdy.openworkflow.spike;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.MDC;

/**
 * A plain CDI bean that a Java DSL function task calls (Q09). Also reports MDC and thread (Q10).
 * Unremovable because it is only looked up programmatically (Arc.container()); without it Quarkus drops the bean.
 */
@ApplicationScoped
@Unremovable
public class GreetingService {

    public String greet(String name) {
        return "hello " + name;
    }

    /** Returns what the calling thread sees in MDC under the key "spikeCorrelation". */
    public String mdcValue() {
        return String.valueOf(MDC.get("spikeCorrelation"));
    }

    public String threadName() {
        return Thread.currentThread().getName();
    }
}
