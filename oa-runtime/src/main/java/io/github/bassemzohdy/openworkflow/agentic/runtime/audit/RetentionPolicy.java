package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which payload form each event type uses (M15). The default applies to every type without its own rule.
 *
 * <p>A redacted field is replaced by {@code redacted:sha256:<digest>}, where the digest covers a deployment salt, the
 * field path and the value. The salt is required when any rule redacts, so that the digest cannot be recomputed by
 * someone who does not hold it, for low-entropy values.
 */
public final class RetentionPolicy {

    public static final int MAX_REDACTED_PATHS = 100;

    /**
     * The form and the redacted paths for one event type.
     *
     * @param form          how the payload of this type is kept
     * @param redactedPaths field paths replaced by a salted hash; only allowed with {@link RetentionForm#STORE_REDACTED}
     */
    public record Rule(RetentionForm form, Set<String> redactedPaths) {

        public Rule {
            Objects.requireNonNull(form, "form");
            redactedPaths = Checks.copyTokens("redactedPaths", redactedPaths, MAX_REDACTED_PATHS,
                    java.util.regex.Pattern.compile("[^\\s]{1,256}"));
            if (form != RetentionForm.STORE_REDACTED && !redactedPaths.isEmpty()) {
                throw new IllegalArgumentException("redacted paths apply only to STORE_REDACTED");
            }
        }

        /** A rule without redacted paths. */
        public static Rule of(RetentionForm form) {
            return new Rule(form, Set.of());
        }
    }

    private final RetentionForm defaultForm;
    private final Map<String, Rule> rules;
    private final String redactionSalt;

    private RetentionPolicy(RetentionForm defaultForm, Map<String, Rule> rules, String redactionSalt) {
        this.defaultForm = Objects.requireNonNull(defaultForm, "defaultForm");
        this.rules = Map.copyOf(rules);
        this.redactionSalt = redactionSalt;
    }

    /**
     * A policy with a default form and per-type rules. Throws when a rule or the default redacts and no salt is given.
     *
     * @param defaultForm   the form for event types without a rule
     * @param rules         rules by event type (the {@code action} of the event)
     * @param redactionSalt the salt for redacted fields, or null
     */
    public static RetentionPolicy of(RetentionForm defaultForm, Map<String, Rule> rules, String redactionSalt) {
        Objects.requireNonNull(rules, "rules");
        rules.keySet().forEach(type -> Checks.requireText("event type", type, 512));
        String salt = redactionSalt == null || redactionSalt.isBlank() ? null : redactionSalt;
        boolean redacts = defaultForm == RetentionForm.STORE_REDACTED
                || rules.values().stream().anyMatch(rule -> rule.form() == RetentionForm.STORE_REDACTED);
        if (redacts && salt == null) {
            throw new IllegalArgumentException("STORE_REDACTED needs a redaction salt (agentic.audit.redaction-salt)");
        }
        return new RetentionPolicy(defaultForm, rules, salt);
    }

    /** Reference-only storage for every type, with no rules and no salt: the safe default. */
    public static RetentionPolicy referenceOnlyByDefault() {
        return of(RetentionForm.STORE_REFERENCE_ONLY, Map.of(), null);
    }

    /** The rule for {@code eventType}: its own rule, or the default form with no redacted paths. */
    public Rule ruleFor(String eventType) {
        return rules.getOrDefault(eventType, Rule.of(defaultForm));
    }

    /** The salt for redacted fields, when configured. */
    public Optional<String> redactionSalt() {
        return Optional.ofNullable(redactionSalt);
    }

    /** The forms in use, for diagnostics. */
    public Set<RetentionForm> formsInUse() {
        EnumSet<RetentionForm> forms = EnumSet.of(defaultForm);
        rules.values().forEach(rule -> forms.add(rule.form()));
        return Set.copyOf(forms);
    }
}
