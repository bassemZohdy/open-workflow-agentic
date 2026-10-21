package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** The default: no approved context exists, so every governed model call is refused. */
@DefaultBean
@ApplicationScoped
public class NoApprovedModelContext implements ApprovedModelContext {

    @Override
    public Optional<Approved> current() {
        return Optional.empty();
    }
}
