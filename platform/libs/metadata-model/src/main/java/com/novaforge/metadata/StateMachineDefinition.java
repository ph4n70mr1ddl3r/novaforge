package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Optional;

/**
 * A document state machine (PHASE-4 §3): first-class metadata enforced by the Data
 * Runtime write path — like validations, not a Workflow-Service concern. The single
 * write path stays absolute: no service can transition a record around the engine,
 * and the {@code transitionState} primitive compiles to a guarded write through the
 * same check.
 *
 * <p>Schema rules (validated at save, guards compiled at publish): {@code stateField}
 * must be an enum field on the bound entity; {@code initial} ∈ states; every
 * transition references known states; terminal states have no outgoing transitions.
 * One state machine per entity in v1.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StateMachineDefinition(
        String id,
        String entity,
        String stateField,
        String initial,
        List<State> states,
        List<Transition> transitions) {

    public StateMachineDefinition {
        states = states == null ? List.of() : List.copyOf(states);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }

    public Optional<State> state(String name) {
        return states.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    public boolean isTerminal(String name) {
        return state(name).map(State::terminalOn).orElse(false);
    }

    /** The listed transition {@code from → to}, if any — the write path's gate. */
    public Optional<Transition> transition(String from, String to) {
        return transitions.stream()
                .filter(t -> t.from().equals(from) && t.to().equals(to))
                .findFirst();
    }

    /** One state of the machine; {@code terminal} states admit no outgoing transitions. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(String name, Boolean terminal) {

        public boolean terminalOn() {
            return Boolean.TRUE.equals(terminal);
        }
    }

    /** A listed edge; {@code guard} is a record-context expression compiled at publish. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Transition(String from, String to, String guard) {
    }
}
