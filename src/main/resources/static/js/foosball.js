// Foosball Management JavaScript

function foosballGameForm() {
  return {
    whiteTeamPlayer1: "",
    whiteTeamPlayer2: "",
    blackTeamPlayer1: "",
    blackTeamPlayer2: "",
    winner: "",

    init() {
      const modal = document.getElementById("addGameModal");
      modal?.addEventListener("shown.bs.modal", () => this.loadLastGameTeams());
    },

    loadLastGameTeams() {
      const tenantSlug = document.body.dataset.tenantSlug;
      if (!tenantSlug) {
        return;
      }

      fetch(`/foosball/${tenantSlug}/fragments/last-game-teams`)
        .then((response) => {
          if (!response.ok) {
            throw new Error(
              `Unable to load last game teams (${response.status})`
            );
          }
          return response.text();
        })
        .then((html) => {
          const fragment = document
            .createRange()
            .createContextualFragment(html)
            .querySelector("[data-last-game-teams]");
          if (!fragment) {
            return;
          }

          const wp1 = fragment.getAttribute("data-white-player-1");
          const wp2 = fragment.getAttribute("data-white-player-2");
          const bp1 = fragment.getAttribute("data-black-player-1");
          const bp2 = fragment.getAttribute("data-black-player-2");

          if (wp1) this.whiteTeamPlayer1 = wp1;
          if (wp2) this.whiteTeamPlayer2 = wp2;
          if (bp1) this.blackTeamPlayer1 = bp1;
          if (bp2) this.blackTeamPlayer2 = bp2;
        })
        .catch((error) =>
          console.error("Error loading last game teams:", error)
        );
    },

    reset() {
      this.whiteTeamPlayer1 = "";
      this.whiteTeamPlayer2 = "";
      this.blackTeamPlayer1 = "";
      this.blackTeamPlayer2 = "";
      this.winner = "";
    },
  };
}

document.addEventListener("alpine:init", () => {
  Alpine.data("foosballGameForm", foosballGameForm);
});

document.addEventListener("DOMContentLoaded", () => {
  document.addEventListener("submit", (event) => {
    const form = event.target;
    const matchId = form.dataset.matchId;
    if (matchId) {
      event.preventDefault();
      window.submitScore?.(event, Number.parseInt(matchId, 10));
    }
  });
});
