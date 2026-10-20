package io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source;

import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Checked source-to-SDK boundary. Callers must approve source and resources before
 * calling; the result confers no identity, target authority or execution permission.
 * Raw SDK APIs bypass this boundary and are not an approved authoring API. */
public final class WorkflowSourceMaterializer {
    private final WorkflowSourceCodec codec;

    public WorkflowSourceMaterializer(WorkflowSourceCodec codec) {
        this.codec = Objects.requireNonNull(codec);
    }

    public static final class Refused extends IllegalArgumentException {
        private final List<WorkflowSourceCodec.Diagnostic> diagnostics;
        private Refused(List<WorkflowSourceCodec.Diagnostic> diagnostics) {
            super("Source materialization refused; inspect bounded diagnostics");
            this.diagnostics = List.copyOf(diagnostics);
        }
        public List<WorkflowSourceCodec.Diagnostic> diagnostics() { return diagnostics; }
    }

    /** Immutable checked view for separate source/resource approval, not an approval token. */
    public static final class Prepared {
        private final byte[] json;
        private final String originalSha256, executableSha256;
        private final WorkflowSourceCodec.Profile profile;
        private Prepared(byte[] json, WorkflowSourceCodec.Imported source) {
            this.json = json.clone(); originalSha256 = source.originalSha256(); profile = source.profile();
            try { executableSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.json)); }
            catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
        }
        public byte[] executableJson() { return json.clone(); }
        public String originalSha256() { return originalSha256; }
        public String executableSha256() { return executableSha256; }
        public WorkflowSourceCodec.Profile profile() { return profile; }
    }

    /** Offline: check ALL declarations and produce exact JSON bytes/digests before
     * approval, SDK binding or resource access. Expands YAML aliases with explicit
     * conversion acknowledgement; original source remains available from Imported. */
    public Prepared prepare(WorkflowSourceCodec.Imported source, WorkflowSourceCodec.ConversionPolicy conversion) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(conversion);
        var diagnostics = codec.runtimeCapabilityDiagnostics(source);
        if (!diagnostics.isEmpty()) throw new Refused(diagnostics);
        var executable = codec.export(source, WorkflowSourceCodec.Format.JSON, conversion);
        if (!executable.succeeded()) throw new Refused(executable.diagnostics());
        return new Prepared(executable.bytes(), source);
    }

    /** Caller must first approve the exact prepared view and resources. Creates no
     * instance and starts no execution. Supported external schemas may be fetched. */
    public WorkflowDefinition materialize(Prepared prepared, WorkflowApplication application) throws IOException {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(application);
        io.serverlessworkflow.api.types.Workflow workflow;
        try {
            workflow = WorkflowReader.readWorkflow(prepared.json, WorkflowFormat.JSON);
        } catch (IOException | RuntimeException unsupported) {
            // SDK exceptions can echo source data. Do not expose them as diagnostic text/causes.
            throw new Refused(List.of(new WorkflowSourceCodec.Diagnostic(
                    WorkflowSourceCodec.Code.SDK_BINDING_UNSUPPORTED, "", "sdk-binding")));
        }
        return application.workflowDefinition(workflow);
    }
}
