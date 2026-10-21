package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Qualifies the PEP that decides model calls, so it never shares an engine with the operations policy. */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({FIELD, PARAMETER, METHOD, TYPE})
public @interface ModelPolicy {
}
