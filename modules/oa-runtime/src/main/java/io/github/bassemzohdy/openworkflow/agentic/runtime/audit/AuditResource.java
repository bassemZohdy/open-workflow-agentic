package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
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
        CallerAuthority who = callers.resolveAuthority(headers);
        access.require(AuditAccess.READ, who.caller());
        AuditQuery.Filter filter = new AuditQuery.Filter(instanceId, correlationId, badRequestOnInvalid(
                () -> chainId == null ? null : AuditKeys.requireChainId(chainId)), type, instant("from", from),
                instant("to", to));
        PageRequest paging = badRequestOnInvalid(() -> PageRequest.of(cursor, limit));
        current(who);
        AuditQuery.Page page = badRequestOnInvalid(() -> query.find(filter, paging));
        return new EventPage(page.items().stream().map(EventView::of).toList(), page.nextCursor());
    }

    @GET
    @Path("/events/{eventId}")
    public EventView event(@Context HttpHeaders headers, @PathParam("eventId") String eventId) {
        CallerAuthority who = callers.resolveAuthority(headers);
        access.require(AuditAccess.READ, who.caller());
        Ulid id = badRequestOnInvalid(() -> Ulid.parse(eventId));
        current(who);
        return query.byId(id).map(EventView::of)
                .orElseThrow(() -> new NotFoundException("no audit event with that id"));
    }

    @POST
    @Path("/verify")
    public VerifyResult verify(@Context HttpHeaders headers, @QueryParam("chainId") String chainId) {
        CallerAuthority who = callers.resolveAuthority(headers);
        access.require(AuditAccess.VERIFY, who.caller());
        String chain = chainId == null ? null : badRequestOnInvalid(() -> AuditKeys.requireChainId(chainId));
        current(who);
        List<ChainReport> reports = chain == null ? verifier.verifyAll() : List.of(verifier.verify(chain));
        return new VerifyResult(reports.stream().allMatch(ChainReport::intact), reports);
    }

    /**
     * The signed expiry of a verified token is judged again after policy, audit and parsing, immediately before any
     * query or verification I/O: 401 when it has passed or the authority is gone, 503 when that cannot be decided. The
     * development header seam has no signed expiry and is not rechecked (it exists only in dev and test profiles).
     */
    private static void current(CallerAuthority who) {
        if (!who.caller().verified()) {
            return;
        }
        try {
            who.requireCurrent(java.util.Set.of());
        } catch (AuthorityLostException lost) {
            if (lost.reason() == AuthorityLostException.Reason.UNAVAILABLE) {
                throw new io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException(
                        jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE,
                        "the caller's authority could not be checked; nothing was read");
            }
            throw new jakarta.ws.rs.WebApplicationException("the caller's authority is no longer valid",
                    jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.UNAUTHORIZED)
                            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"").build());
        }
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
