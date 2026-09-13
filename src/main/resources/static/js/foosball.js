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

  document
    .getElementById("createTournamentForm")
    ?.addEventListener("submit", createTournament);
});

async function createTournament(event) {
  event.preventDefault();

  const form = event.currentTarget;
  const formData = new FormData(form);
  const tenantSlug = document.body.dataset.tenantSlug;
  const createdById = Number.parseInt(formData.get("createdById"), 10);
  const submitButton = form
    .closest(".modal-content")
    .querySelector('[type="submit"]');
  submitButton.disabled = true;

  const request = {
    name: formData.get("name"),
    description: formData.get("description"),
    tournamentType: formData.get("tournamentType") || "SINGLE_ELIMINATION",
    maxParticipants: formData.get("maxParticipants")
      ? Number.parseInt(formData.get("maxParticipants"), 10)
      : null,
  };

  try {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector(
      'meta[name="_csrf_header"]'
    ).content;
    const response = await fetch(
      `/api/tournaments/${tenantSlug}?createdById=${createdById}`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          [csrfHeader]: csrfToken,
        },
        body: JSON.stringify(request),
      }
    );

    if (!response.ok) {
      throw new Error(`Failed to create tournament (${response.status})`);
    }

    const tournament = await response.json();
    bootstrap.Modal.getInstance(
      document.getElementById("createTournamentModal")
    ).hide();
    form.reset();
    window.location.href = `/foosball/${tenantSlug}/tournaments/${tournament.id}`;
  } catch (error) {
    console.error("Error creating tournament:", error);
    alert("Failed to create tournament");
    submitButton.disabled = false;
  }
}
