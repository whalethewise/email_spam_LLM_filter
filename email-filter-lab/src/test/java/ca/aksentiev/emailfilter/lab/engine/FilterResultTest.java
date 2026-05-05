package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterResultTest {

    @Test
    void leaveFactoriesProduceEmptyActionList() {
        assertThat(FilterResult.leave().actions()).isEmpty();
        assertThat(FilterResult.leave("whitelisted").actions()).isEmpty();
        assertThat(FilterResult.leave("whitelisted").reason()).isEqualTo("whitelisted");
    }

    @Test
    void isLeaveTrueForEmptyOrAllLeaveActions() {
        assertThat(FilterResult.leave().isLeave()).isTrue();

        FilterResult onlyLeaveAction = new FilterResult(
                List.of(ResolvedAction.of(ActionType.LEAVE)), 0, null, null, null);
        assertThat(onlyLeaveAction.isLeave()).isTrue();
    }

    @Test
    void isLeaveFalseWhenAnyConcreteActionPresent() {
        FilterResult flagged = new FilterResult(
                List.of(ResolvedAction.of(ActionType.FLAG)), 0, null, null, null);
        assertThat(flagged.isLeave()).isFalse();
    }

    @Test
    void isDestructiveDetectsMoveAndDelete() {
        assertThat(destructiveResult(ActionType.MOVE_TO_FOLDER).isDestructive()).isTrue();
        assertThat(destructiveResult(ActionType.MOVE_TO_JUNK).isDestructive()).isTrue();
        assertThat(destructiveResult(ActionType.DELETE).isDestructive()).isTrue();
    }

    @Test
    void isDestructiveFalseForFlagSendEmailLeave() {
        assertThat(destructiveResult(ActionType.FLAG).isDestructive()).isFalse();
        assertThat(destructiveResult(ActionType.SEND_EMAIL).isDestructive()).isFalse();
        assertThat(destructiveResult(ActionType.LEAVE).isDestructive()).isFalse();
        assertThat(FilterResult.leave().isDestructive()).isFalse();
    }

    @Test
    void primaryActionTypePrefersDestructiveOverFlag() {
        FilterResult mixed = new FilterResult(
                List.of(
                        ResolvedAction.of(ActionType.FLAG),
                        ResolvedAction.moveToFolder("Deals")),
                0, null, null, null);

        assertThat(mixed.primaryActionType()).isEqualTo(ActionType.MOVE_TO_FOLDER);
    }

    @Test
    void primaryActionTypeFallsBackToFirstWhenNoDestructive() {
        FilterResult flagOnly = new FilterResult(
                List.of(
                        ResolvedAction.of(ActionType.FLAG),
                        ResolvedAction.sendEmail("a@b.com", "s", "b")),
                0, null, null, null);

        assertThat(flagOnly.primaryActionType()).isEqualTo(ActionType.FLAG);
    }

    @Test
    void primaryActionTypeReturnsLeaveForEmpty() {
        assertThat(FilterResult.leave().primaryActionType()).isEqualTo(ActionType.LEAVE);
    }

    @Test
    void actionListIsImmutable() {
        FilterResult result = new FilterResult(
                List.of(ResolvedAction.of(ActionType.FLAG)), 0, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> result.actions().add(ResolvedAction.of(ActionType.DELETE)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private FilterResult destructiveResult(ActionType type) {
        ResolvedAction action = type == ActionType.MOVE_TO_FOLDER
                ? ResolvedAction.moveToFolder("Anywhere")
                : ResolvedAction.of(type);
        return new FilterResult(List.of(action), 0, null, null, null);
    }
}
