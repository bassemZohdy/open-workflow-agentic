package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Query and verification API of the audit trail (M15).
 *
 * <ul>
 *   <li>{@code GET /audit/events}: filters {@code instanceId}, {@code correlationId}, {@code chainId}, {@code type},
 *       {@code from} and {@code to} (RFC 3339 instants; {@code to} exclusive); cursor pagination with
 *       {@code cursor} and {@code limit} (default 50, at most 200).</li>
 *   <li>{@code GET /audit/events/{eventId}}.</li>
 *   <li>{@code POST /audit/verify}: verifies every chain, or the one named by {@code chainId}.</li>
 * </ul>
 *
 * Every endpoint first establishes the caller ({@link CallerContextResolver}: 401 without a usable credential, 503 when
 * the token cannot be checked) and then asks {@link AuditAccess} about that caller (403 when refused). Nothing is read
 * before both have passed. Errors are RFC 9457 problem documents.
 */
@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    /** One event as the API shows it. Timestamps are RFC 3339 strings. */
    public record EventView(String eventId, String chainId, long sequence, String occurredAt, String type,
                            Actor actor, String resourceType, String resourceId, String previousHash, String hash,
                            Map<String, String> attributes) {

        public record Actor(String kind, String id) {
        }

        static EventView of(AuditEvent event) {
            return new EventView(event.id().value(), AuditQuery.chainOf(event), event.sequence(),
                    event.occurredAt().toString(), event.action(),
                    new Actor(event.actor().kind().name(), event.actor().id()), event.resourceType(),
                    event.resourceId(), event.previousHash(), event.hash(), event.attributes());
        }
    }

    /** A page of events. {@code nextCursor} is null on the last page. */
    public record EventPage(List<EventView> items, String nextCursor) {
    }

    /** The outcome of a verification run. {@code intact} is true only when every verified chain is intact. */
    public record VerifyResult(boolean intact, List<ChainReport> chains) {
    }

    @Inject
    AuditAccess access;

    @Inject
    CallerContextResolver callers;

    @Inject
    AuditQuery query;

    @Inject
    ChainVerifier verifier;

    @GET
    @Path("/events")
    public EventPage events(@Context HttpHeaders headers,
                            @QueryParam("instanceId") String instanceId,
                            @QueryParam("correlationId") String correlationId,
                            @QueryParam("chainId") String chainId,
                            @QueryParam("type") String type,
                            @QueryParam("from") String from,
                            @QueryParam("to") String to,
                            @QueryParam("cursor") String cursor,
                            @QueryParam("limit") Integer limit) {
        access.require(AuditAccess.READ, callers.resolve(headers));
        AuditQuery.Filter filter = new AuditQuery.Filter(instanceId, correlationId, badRequestOnInvalid(
                () -> chainId == null ? null : AuditKeys.requireChainId(chainId)), type, instant("from", from),
                instant("to", to));
        AuditQuery.Page page = badRequestOnInvalid(() -> query.find(filter, PageRequest.of(cursor, limit)));
        return new EventPage(page.items().stream().map(EventView::of).toList(), page.nextCursor());
    }

    @GET
    @Path("/events/{eventId}")
    public EventView event(@Context HttpHeaders headers, @PathParam("eventId") String eventId) {
        access.require(AuditAccess.READ, callers.resolve(headers));
        Ulid id = badRequestOnInvalid(() -> Ulid.parse(eventId));
        return query.byId(id).map(EventView::of)
                .orElseThrow(() -> new NotFoundException("no audit event with that id"));
    }

    @POST
    @Path("/verify")
    public VerifyResult verify(@Context HttpHeaders headers, @QueryParam("chainId") String chainId) {
        access.require(AuditAccess.VERIFY, callers.resolve(headers));
        List<ChainReport> reports = chainId == null
                ? verifier.verifyAll()
                : List.of(verifier.verify(badRequestOnInvalid(() -> AuditKeys.requireChainId(chainId))));
        return new VerifyResult(reports.stream().allMatch(ChainReport::intact), reports);
    }

    private static Instant instant(String name, String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(name + " must be an RFC 3339 instant, for example 2026-10-18T09:00:00Z");
        }
    }

    /** Turns an argument error into a 400 problem; the message names the argument, not the value. */
    private static <T> T badRequestOnInvalid(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
