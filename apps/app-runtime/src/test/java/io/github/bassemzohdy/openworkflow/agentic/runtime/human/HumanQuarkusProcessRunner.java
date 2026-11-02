package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.PrintWriter;

/** Runs one named Quarkus test phase in its own JVM, with a non-zero status on any failure. */
public final class HumanQuarkusProcessRunner {
    public static void main(String[] args) {
        if (args.length != 1 || !args[0].matches("[A-Za-z][A-Za-z0-9]*")) throw new IllegalArgumentException();
        var request=LauncherDiscoveryRequestBuilder.request()
                .selectors(selectMethod("io.github.bassemzohdy.openworkflow.agentic.runtime.human.HumanWakeProcessPhase#"+args[0])).build();
        var listener=new SummaryGeneratingListener();
        var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var summary=listener.getSummary();summary.printFailuresTo(new PrintWriter(System.err,true));
        if(summary.getTestsFoundCount()!=1 || summary.getTestsSucceededCount()!=1)System.exit(1);
    }
}
