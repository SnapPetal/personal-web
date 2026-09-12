package biz.thonbecker.personal.foosball.platform.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PlayerStatsRepository extends Repository<Player, Long> {

    @Query(
            value =
                    "SELECT id, name, rating, peak_rating, current_streak, best_streak, games_played, total_games, wins, win_percentage FROM foosball.player_stats WHERE tenant_id = :tenantId ORDER BY rating DESC, win_percentage DESC, total_games DESC",
            nativeQuery = true)
    List<PlayerStats> findAllPlayerStatsOrderedByWinPercentage(@Param("tenantId") long tenantId);

    // Removed: Old rank_score calculation replaced with ELO rating system
    // Use Player.rating field instead via PlayerRepository

    @Query(
            value =
                    "SELECT id, name, rating, peak_rating, current_streak, best_streak, games_played, total_games, wins, win_percentage FROM foosball.player_stats WHERE tenant_id = :tenantId ORDER BY total_games DESC, win_percentage DESC",
            nativeQuery = true)
    List<PlayerStats> findAllPlayerStatsOrderedByTotalGames(@Param("tenantId") long tenantId);

    @Query(
            value =
                    "SELECT id, name, rating, peak_rating, current_streak, best_streak, games_played, total_games, wins, win_percentage FROM foosball.player_stats WHERE tenant_id = :tenantId ORDER BY wins DESC, win_percentage DESC",
            nativeQuery = true)
    List<PlayerStats> findAllPlayerStatsOrderedByWins(@Param("tenantId") long tenantId);

    @Query(
            value =
                    "SELECT id, name, rating, peak_rating, current_streak, best_streak, games_played, total_games, wins, win_percentage FROM foosball.player_stats WHERE tenant_id = :tenantId AND total_games >= :minGames ORDER BY rating DESC, win_percentage DESC",
            nativeQuery = true)
    List<PlayerStats> findTopPlayersByWinPercentage(@Param("tenantId") long tenantId, @Param("minGames") int minGames);

    @Query(
            value =
                    "SELECT id, name, rating, peak_rating, current_streak, best_streak, games_played, total_games, wins, win_percentage FROM foosball.player_stats WHERE tenant_id = :tenantId AND total_games >= :minGames ORDER BY total_games DESC",
            nativeQuery = true)
    List<PlayerStats> findTopPlayersByTotalGames(@Param("tenantId") long tenantId, @Param("minGames") int minGames);

    @Query(
            value =
                    "SELECT id, name, rating, peak_rating, current_streak, best_streak, games_played, total_games, wins, win_percentage FROM foosball.player_stats WHERE tenant_id = :tenantId AND total_games >= :minGames ORDER BY wins DESC",
            nativeQuery = true)
    List<PlayerStats> findTopPlayersByWins(@Param("tenantId") long tenantId, @Param("minGames") int minGames);
}
