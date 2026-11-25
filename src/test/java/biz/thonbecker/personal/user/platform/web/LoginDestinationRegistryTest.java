package biz.thonbecker.personal.user.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginDestinationRegistryTest {

    @Test
    void acceptsBudgetAsAnApplicationLoginDestination() {
        assertThat(LoginDestinationRegistry.findPath("/budget")).contains("/budget");
    }

    @Test
    void rejectsPathsOutsideTheApplicationLoginDestinations() {
        assertThat(LoginDestinationRegistry.findPath("https://github.com/SnapPetal/budget"))
                .isEmpty();
    }
}
