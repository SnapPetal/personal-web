package biz.thonbecker.personal.foosball;

import static org.assertj.core.api.Assertions.assertThat;

import biz.thonbecker.personal.IntegrationTest;
import biz.thonbecker.personal.foosball.platform.FoosballDataService;
import biz.thonbecker.personal.foosball.platform.persistence.TenantRepository;
import biz.thonbecker.personal.foosball.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class FoosballTenantIsolationTest {

    @Autowired
    private FoosballDataService foosballDataService;

    @Autowired
    private TenantRepository tenantRepository;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void playerReadsOnlyReturnPlayersForCurrentTenant() {
        final var ramsey = tenantRepository.findBySlug("ramsey-solutions").orElseThrow();
        final var playground = tenantRepository.findBySlug("playground").orElseThrow();

        TenantContext.set(ramsey.getId());
        final var ramseyPlayer = foosballDataService.createPlayer("Ramsey Isolation Test");

        TenantContext.set(playground.getId());
        final var playgroundPlayer = foosballDataService.createPlayer("Playground Isolation Test");

        assertThat(foosballDataService.getAllPlayers())
                .extracting(player -> player.getName())
                .contains(playgroundPlayer.getName())
                .doesNotContain(ramseyPlayer.getName());

        TenantContext.set(ramsey.getId());
        assertThat(foosballDataService.getAllPlayers())
                .extracting(player -> player.getName())
                .contains(ramseyPlayer.getName())
                .doesNotContain(playgroundPlayer.getName());
    }
}
