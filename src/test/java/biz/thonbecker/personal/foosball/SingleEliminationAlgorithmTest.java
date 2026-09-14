package biz.thonbecker.personal.foosball;

import static org.assertj.core.api.Assertions.assertThat;

import biz.thonbecker.personal.foosball.platform.persistence.Player;
import biz.thonbecker.personal.foosball.platform.persistence.Tournament;
import biz.thonbecker.personal.foosball.platform.persistence.TournamentMatch;
import biz.thonbecker.personal.foosball.platform.persistence.TournamentRegistration;
import biz.thonbecker.personal.foosball.platform.tournament.algorithm.SingleEliminationAlgorithm;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SingleEliminationAlgorithmTest {

    private final SingleEliminationAlgorithm algorithm = new SingleEliminationAlgorithm();

    @Test
    void distributesByesForTwelveParticipants() {
        final var tournament =
                new Tournament("Test Tournament", Tournament.TournamentType.SINGLE_ELIMINATION, new Player("Owner"));
        final var registrations = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> new TournamentRegistration(tournament, new Player("Player " + index)))
                .toList();

        final var matches = algorithm.generateBracket(tournament, registrations);
        tournament.getMatches().addAll(matches);

        final var firstRound =
                matches.stream().filter(match -> match.getRoundNumber() == 1).toList();
        final var secondRound =
                matches.stream().filter(match -> match.getRoundNumber() == 2).toList();

        assertThat(firstRound).hasSize(8);
        assertThat(firstRound.stream().filter(match -> match.getStatus() == TournamentMatch.MatchStatus.WALKOVER))
                .hasSize(4);
        assertThat(firstRound.stream().filter(match -> match.getStatus() == TournamentMatch.MatchStatus.READY))
                .hasSize(4);
        assertThat(secondRound.stream().filter(match -> match.getStatus() == TournamentMatch.MatchStatus.READY))
                .hasSize(2);
        assertThat(secondRound.stream().filter(match -> match.getStatus() == TournamentMatch.MatchStatus.PENDING))
                .hasSize(2);
        assertThat(matches)
                .noneMatch(match ->
                        match.getStatus() == TournamentMatch.MatchStatus.PENDING && match.getRoundNumber() == 1);
    }
}
