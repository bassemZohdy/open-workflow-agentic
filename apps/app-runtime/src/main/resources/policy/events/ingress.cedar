// Producer role check. EventIngress independently requires an exact deployment binding for
// verified tenant/subject/source/type/definition/correlation; this private PEP cannot bypass it.
@id("events.receive-scoped-producer")
permit (principal, action == Action::"data_access", resource)
when { context.scopes.contains("oa.events.receive") };
