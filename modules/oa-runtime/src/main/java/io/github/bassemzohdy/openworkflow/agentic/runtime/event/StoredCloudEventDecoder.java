package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import java.io.ByteArrayInputStream;
import java.util.Objects;

/**
 * Reconstruct the complete ingress envelope for SDK predicates/callbacks. This grants no dispatch or routing
 * authority: tenant/target/correlation still come from the durable receipt and registered occurrence. Extensions
 * remain untrusted event data. Decode anew for each delivery, without sharing mutable event data between callbacks.
 */
public final class StoredCloudEventDecoder {
    private StoredCloudEventDecoder() { }

    public static CloudEvent decode(InboxMessage receipt) {
        Objects.requireNonNull(receipt,"receipt");
        StructuredEvent parsed;
        try {
            parsed=StructuredEvent.read(new ByteArrayInputStream(CanonicalEncoder.strictUtf8(receipt.dataJson())));
        } catch(ProblemException invalid) {
            throw new IllegalStateException("stored event is not a bounded structured CloudEvent",invalid);
        }
        if(!receipt.eventId().equals(parsed.id()) || !receipt.source().equals(parsed.source())
                || !receipt.type().equals(parsed.type()))
            throw new IllegalStateException("stored CloudEvent does not match its durable receipt");
        return parsed.event(); // dataJson is the WHOLE envelope, not the CloudEvent's data value.
    }
}
