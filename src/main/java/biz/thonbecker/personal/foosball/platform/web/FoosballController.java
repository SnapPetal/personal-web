package biz.thonbecker.personal.foosball.platform.web;

import biz.thonbecker.personal.foosball.domain.Game;
import biz.thonbecker.personal.foosball.domain.GameResult;
import biz.thonbecker.personal.foosball.domain.Player;
import biz.thonbecker.personal.foosball.domain.Team;
import biz.thonbecker.personal.foosball.platform.FoosballService;
import biz.thonbecker.personal.foosball.platform.TournamentService;
import biz.thonbecker.personal.foosball.platform.persistence.TenantRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Foosball controller - handles web endpoints for foosball functionality.
 * Delegates to FoosballService for business logic.
 */
@Controller
@RequestMapping("/foosball/{tenantSlug}")
@Slf4j
public class FoosballController {

    private final FoosballService foosballService;
    private final TournamentService tournamentService;
    private final TenantRepository tenantRepository;

    public FoosballController(
            FoosballService foosballService, TournamentService tournamentService, TenantRepository tenantRepository) {
        this.foosballService = foosballService;
        this.tournamentService = tournamentService;
        this.tenantRepository = tenantRepository;
    }

    @GetMapping
    public String foosballPage(Model model) {
        boolean serviceAvailable = foosballService.isServiceAvailable();
        model.addAttribute("tenants", tenantRepository.findAll());
        model.addAttribute("serviceAvailable", serviceAvailable);

        if (serviceAvailable) {
            model.addAttribute("playerStats", foosballService.getPlayerStats());
            model.addAttribute("players", foosballService.getAllPlayers());
        }

        return "foosball";
    }

    @GetMapping("/players")
    public String getPlayers(Model model) {
        model.addAttribute("players", foosballService.getAllPlayers());
        return "foosball-players";
    }

    @GetMapping("/team-stats")
    public String getTeamStats(Model model) {
        model.addAttribute("teamStats", foosballService.getTeamStats());
        return "foosball-team-stats";
    }

    @GetMapping("/recent-games")
    public String getRecentGames(Model model) {
        model.addAttribute("games", foosballService.getRecentGames());
        model.addAttribute("ResultStatus", GameResult.class);
        return "foosball-recent-games";
    }

    @GetMapping("/tournaments")
    public String tournaments(Model model) {
        model.addAttribute("players", foosballService.getAllPlayers());
        return "foosball-tournaments";
    }

    @GetMapping("/tournaments/fragments/list")
    public String tournamentListFragment(Model model, Pageable pageable) {
        model.addAttribute(
                "tournaments",
                tournamentService.getTournamentSummaries(pageable).getContent());
        return "foosball-tournament-fragments :: tournamentList";
    }

    @GetMapping("/tournaments/{id}")
    public String tournamentDetail(@PathVariable Long id, Model model) {
        final var tournament = tournamentService.getTournamentWithRegistrations(id);
        final var bracket = tournamentService.getBracketView(id);
        final var registrations = tournamentService.getTournamentRegistrations(id);
        final var matches = tournamentService.getTournamentMatches(id);

        model.addAttribute("tournament", tournament);
        model.addAttribute("bracket", bracket);
        model.addAttribute("registrations", registrations);
        model.addAttribute("matches", matches);
        model.addAttribute("players", foosballService.getAllPlayers());

        return "foosball-tournament-detail";
    }

    // HTMX Fragment Endpoints
    @GetMapping("/fragments/player-stats")
    public String getPlayerStatsFragment(Model model) {
        model.addAttribute("playerStats", foosballService.getPlayerStats());
        return "foosball-fragments :: playerStatsList";
    }

    @GetMapping("/fragments/games-list")
    public String getGamesListFragment(Model model) {
        model.addAttribute("games", foosballService.getRecentGames());
        return "foosball-fragments :: gamesList";
    }

    @GetMapping("/fragments/player-options")
    public String getPlayerOptionsFragment(Model model) {
        try {
            model.addAttribute("players", foosballService.getAllPlayers());
        } catch (Exception e) {
            // If service is unavailable, provide empty list
            model.addAttribute("players", List.of());
        }
        return "foosball-fragments :: playerOptions";
    }

    @GetMapping("/fragments/last-game-teams")
    public String getLastGameTeamsFragment(Model model) {
        try {
            final var lastGame = foosballService.getLastGame();
            if (lastGame != null) {
                String wp1 = lastGame.getWhiteTeam().getPlayer1();
                String wp2 = lastGame.getWhiteTeam().getPlayer2();
                String bp1 = lastGame.getBlackTeam().getPlayer1();
                String bp2 = lastGame.getBlackTeam().getPlayer2();

                log.info("Last game teams - White: {} & {}, Black: {} & {}", wp1, wp2, bp1, bp2);

                model.addAttribute("whiteTeamPlayer1", wp1);
                model.addAttribute("whiteTeamPlayer2", wp2);
                model.addAttribute("blackTeamPlayer1", bp1);
                model.addAttribute("blackTeamPlayer2", bp2);
                model.addAttribute(
                        "whiteTeamPlayer1Id",
                        foosballService.findPlayerByName(wp1).map(Player::getId).orElse(null));
                model.addAttribute(
                        "whiteTeamPlayer2Id",
                        foosballService.findPlayerByName(wp2).map(Player::getId).orElse(null));
                model.addAttribute(
                        "blackTeamPlayer1Id",
                        foosballService.findPlayerByName(bp1).map(Player::getId).orElse(null));
                model.addAttribute(
                        "blackTeamPlayer2Id",
                        foosballService.findPlayerByName(bp2).map(Player::getId).orElse(null));
            }
        } catch (Exception e) {
            log.error("Error loading last game teams", e);
        }
        return "foosball-fragments :: lastGameTeams";
    }

    @GetMapping("/fragments/player-table")
    public String getPlayerTableFragment(Model model) {
        model.addAttribute("players", foosballService.getAllPlayers());
        return "foosball-players :: playerTable";
    }

    @PostMapping("/htmx/players")
    public String createPlayerHtmx(@RequestParam String name, Model model, HttpServletResponse response) {
        try {
            if (name != null && !name.trim().isEmpty()) {
                Player player = new Player(name.trim());
                foosballService.createPlayer(player);
                model.addAttribute("success", "Player '" + name.trim() + "' added successfully!");
                model.addAttribute("players", foosballService.getAllPlayers());
                response.setHeader("HX-Trigger", "playerUpdate,refresh-players");
            } else {
                model.addAttribute("error", "Please provide a player name.");
            }
        } catch (Exception e) {
            model.addAttribute("error", "Failed to add player. Please try again.");
        }

        return "foosball-fragments :: playerUpdate";
    }

    @PostMapping("/htmx/games")
    public String createGameHtmx(
            @RequestParam Long whiteTeamPlayer1,
            @RequestParam Long whiteTeamPlayer2,
            @RequestParam Long blackTeamPlayer1,
            @RequestParam Long blackTeamPlayer2,
            @RequestParam String winner,
            Model model,
            HttpServletResponse response) {

        try {
            // Validation
            final var whitePlayer1 =
                    foosballService.findPlayerById(whiteTeamPlayer1).orElse(null);
            final var whitePlayer2 =
                    foosballService.findPlayerById(whiteTeamPlayer2).orElse(null);
            final var blackPlayer1 =
                    foosballService.findPlayerById(blackTeamPlayer1).orElse(null);
            final var blackPlayer2 =
                    foosballService.findPlayerById(blackTeamPlayer2).orElse(null);
            if (whitePlayer1 == null || whitePlayer2 == null || blackPlayer1 == null || blackPlayer2 == null) {
                model.addAttribute("error", "Please select all players.");
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
                return "foosball-fragments :: alert";
            }

            if (winner == null || winner.isEmpty()) {
                model.addAttribute("error", "Please select a winner.");
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
                return "foosball-fragments :: alert";
            }

            // Create game using new domain model
            Team whiteTeam = new Team(whitePlayer1.getName(), whitePlayer2.getName());
            Team blackTeam = new Team(blackPlayer1.getName(), blackPlayer2.getName());

            // Map winner string to GameResult
            biz.thonbecker.personal.foosball.domain.GameResult result;
            switch (winner) {
                case "TEAM1":
                    result = biz.thonbecker.personal.foosball.domain.GameResult.WHITE_TEAM_WIN;
                    break;
                case "TEAM2":
                    result = biz.thonbecker.personal.foosball.domain.GameResult.BLACK_TEAM_WIN;
                    break;
                default:
                    model.addAttribute("error", "Invalid winner value.");
                    response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
                    return "foosball-fragments :: alert";
            }

            Game game = new Game(whiteTeam, blackTeam, result);

            Game createdGame = foosballService.createGame(game);
            if (createdGame != null) {
                model.addAttribute("success", "Game recorded successfully!");
                response.setHeader("HX-Trigger", "playerStatsUpdated");
            } else {
                model.addAttribute("error", "Failed to record game. Server returned an empty response.");
                response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
            }
        } catch (Exception e) {
            String errorMessage = "Failed to record game: " + e.getMessage();
            // Log the detailed error
            log.error("Error recording game", e);

            // Extract meaningful message for the user
            if (e.getMessage() != null && e.getMessage().contains("400")) {
                errorMessage = "Invalid game data. Please check all fields and try again.";
            }

            model.addAttribute("error", errorMessage);
            response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        }

        return "foosball-fragments :: alert";
    }
}
