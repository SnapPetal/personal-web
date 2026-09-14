// Foosball Management JavaScript
// Date formatting utility functions
function formatDateTime(dateString) {
  const date = new Date(dateString);
  return date.toLocaleDateString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatDate(dateString) {
  const date = new Date(dateString);
  return date.toLocaleDateString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

function loadLastGameTeams() {
  const modal = document.getElementById("addGameModal");
  if (!modal) {
    return;
  }

  fetch(
    `/foosball/${document.body.dataset.tenantSlug}/fragments/last-game-teams`
  )
    .then((response) => {
      if (!response.ok) {
        throw new Error(`Unable to load last game teams (${response.status})`);
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

      const players = {
        "data-white-player-1": "#whiteTeamPlayer1",
        "data-white-player-2": "#whiteTeamPlayer2",
        "data-black-player-1": "#blackTeamPlayer1",
        "data-black-player-2": "#blackTeamPlayer2",
      };
      Object.entries(players).forEach(([attribute, selector]) => {
        const playerId = fragment.getAttribute(attribute);
        const select = document.querySelector(selector);
        if (playerId && select) {
          select.value = playerId;
        }
      });
    })
    .catch((error) => console.error("Error loading last game teams:", error));
}

document.addEventListener("DOMContentLoaded", () => {
  document
    .getElementById("addGameModal")
    ?.addEventListener("shown.bs.modal", loadLastGameTeams);

  document.addEventListener("submit", (event) => {
    const form = event.target;
    const matchId = form.dataset.matchId;
    if (matchId) {
      event.preventDefault();
      window.submitScore?.(event, Number.parseInt(matchId, 10));
    }
  });
});
