package ca.aksentiev.emailfilter.lab.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies stop-on-first-destructive-action chain semantics
 * (FILTER-SPEC.md §24-27).
 */
@ExtendWith(MockitoExtension.class)
class FilterChainTest {

    @Mock private FilterDispatcher dispatcher;

    private FilterChain chain;

    @BeforeEach
    void setUp() {
        chain = new FilterChain(dispatcher);
    }

    @Test
    void singleFilterChainReturnsThatFiltersActionsAndOneStep() {
        Map<String, FilterDefinition> defs = defs("only");
        FilterResult result = processed(List.of(ResolvedAction.moveToFolder("Inbox")));
        when(dispatcher.dispatch(any(), any())).thenReturn(result);

        ChainResult chainResult = chain.run(List.of("only"), defs, sampleEmail());

        assertThat(chainResult.steps()).hasSize(1);
        assertThat(chainResult.steps().get(0).filterName()).isEqualTo("only");
        assertThat(chainResult.steps().get(0).stoppedChain()).isTrue();  // destructive → stops
        assertThat(chainResult.actions())
                .extracting(ResolvedAction::type)
                .containsExactly(ActionType.MOVE_TO_FOLDER);
    }

    @Test
    void destructiveActionStopsTheChain() {
        Map<String, FilterDefinition> defs = defs("a", "b", "c");
        FilterResult resultA = processed(List.of(ResolvedAction.of(ActionType.FLAG)));
        FilterResult resultB = processed(List.of(ResolvedAction.moveToFolder("Junk")));
        // resultC must never be requested

        when(dispatcher.dispatch(eq(defs.get("a")), any())).thenReturn(resultA);
        when(dispatcher.dispatch(eq(defs.get("b")), any())).thenReturn(resultB);

        ChainResult chainResult = chain.run(List.of("a", "b", "c"), defs, sampleEmail());

        assertThat(chainResult.steps()).hasSize(2);
        assertThat(chainResult.steps().get(0).stoppedChain()).isFalse();
        assertThat(chainResult.steps().get(1).stoppedChain()).isTrue();
        assertThat(chainResult.stoppedAtFilter()).isEqualTo("b");
        // Actions accumulated in order
        assertThat(chainResult.actions())
                .extracting(ResolvedAction::type)
                .containsExactly(ActionType.FLAG, ActionType.MOVE_TO_FOLDER);
        // Filter c was never called
        verify(dispatcher, never()).dispatch(eq(defs.get("c")), any());
    }

    @Test
    void nonDestructiveActionsAccumulateAcrossWholeChain() {
        Map<String, FilterDefinition> defs = defs("a", "b", "c");
        // All non-destructive — chain runs to completion
        FilterResult resultA = processed(List.of(ResolvedAction.of(ActionType.FLAG)));
        FilterResult resultB = FilterResult.leave();
        FilterResult resultC = processed(List.of(
                ResolvedAction.sendEmail("user@example.com", "subj", "body")));

        when(dispatcher.dispatch(eq(defs.get("a")), any())).thenReturn(resultA);
        when(dispatcher.dispatch(eq(defs.get("b")), any())).thenReturn(resultB);
        when(dispatcher.dispatch(eq(defs.get("c")), any())).thenReturn(resultC);

        ChainResult chainResult = chain.run(List.of("a", "b", "c"), defs, sampleEmail());

        assertThat(chainResult.steps()).hasSize(3);
        assertThat(chainResult.stoppedAtFilter()).isNull();
        assertThat(chainResult.actions())
                .extracting(ResolvedAction::type)
                .containsExactly(ActionType.FLAG, ActionType.SEND_EMAIL);
    }

    @Test
    void allFiltersReturnLeaveProducesEmptyActions() {
        Map<String, FilterDefinition> defs = defs("a", "b");
        when(dispatcher.dispatch(any(), any())).thenReturn(FilterResult.leave());

        ChainResult chainResult = chain.run(List.of("a", "b"), defs, sampleEmail());

        assertThat(chainResult.steps()).hasSize(2);
        assertThat(chainResult.actions()).isEmpty();
        assertThat(chainResult.isLeave()).isTrue();
    }

    @Test
    void deleteAlsoStopsChain() {
        Map<String, FilterDefinition> defs = defs("a", "b");
        FilterResult resultA = processed(List.of(ResolvedAction.of(ActionType.DELETE)));

        when(dispatcher.dispatch(eq(defs.get("a")), any())).thenReturn(resultA);

        ChainResult chainResult = chain.run(List.of("a", "b"), defs, sampleEmail());

        assertThat(chainResult.steps()).hasSize(1);
        assertThat(chainResult.stoppedAtFilter()).isEqualTo("a");
        verify(dispatcher, never()).dispatch(eq(defs.get("b")), any());
    }

    @Test
    void unknownFilterNameThrows() {
        Map<String, FilterDefinition> defs = defs("a");

        // Place the missing name first so the chain fails before any dispatch
        assertThatThrownBy(() -> chain.run(List.of("missing", "a"), defs, sampleEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void sendEmailDoesNotStopTheChain() {
        Map<String, FilterDefinition> defs = defs("a", "b");
        FilterResult resultA = processed(List.of(
                ResolvedAction.sendEmail("paul@example.com", "subj", "body")));
        FilterResult resultB = processed(List.of(ResolvedAction.moveToFolder("Done")));

        when(dispatcher.dispatch(eq(defs.get("a")), any())).thenReturn(resultA);
        when(dispatcher.dispatch(eq(defs.get("b")), any())).thenReturn(resultB);

        ChainResult chainResult = chain.run(List.of("a", "b"), defs, sampleEmail());

        assertThat(chainResult.steps()).hasSize(2);
        assertThat(chainResult.actions())
                .extracting(ResolvedAction::type)
                .containsExactly(ActionType.SEND_EMAIL, ActionType.MOVE_TO_FOLDER);
        assertThat(chainResult.stoppedAtFilter()).isEqualTo("b");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, FilterDefinition> defs(String... names) {
        Map<String, FilterDefinition> map = new LinkedHashMap<>();
        for (String name : names) {
            // Set ollamaModel to the name so each FilterDefinition is record-distinct
            map.put(name, new FilterDefinition(
                    FilterType.LOGISTICS, true, name,
                    null, null, null, null,
                    null, null, null, null, null, null));
        }
        return map;
    }

    private FilterResult processed(List<ResolvedAction> actions) {
        return new FilterResult(actions, 0, null, null, null);
    }

    private EmailMessage sampleEmail() {
        return new EmailMessage(
                "<id>", "sender@example.com", "Sender", List.of("me@example.com"),
                "subject", "body", "", null, Map.of());
    }
}
