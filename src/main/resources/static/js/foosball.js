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
    .then((response) => response.text())
    .then((html) => {
      const fragment = document
        .createRange()
        .createContextualFragment(html)
        .querySelector("[data-last-game-teams]");
      if (!fragment) {
        return;
      }

      const selectors = {
        whitePlayer1: "#whiteTeamPlayer1",
        whitePlayer2: "#whiteTeamPlayer2",
        blackPlayer1: "#blackTeamPlayer1",
        blackPlayer2: "#blackTeamPlayer2",
      };
      Object.entries(selectors).forEach(([player, selector]) => {
        const playerId = fragment.dataset[player];
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
});
