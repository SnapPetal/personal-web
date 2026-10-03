(() => {
  const button = document.querySelector("[data-joke-toggle]");
  const audio = document.querySelector("[data-joke-audio]");
  if (!button || !audio) return;

  button.addEventListener("click", async () => {
    if (!audio.paused) {
      audio.pause();
      audio.currentTime = 0;
      button.textContent = "😂";
      button.setAttribute("aria-label", "Play dad joke of the day");
      return;
    }

    button.disabled = true;
    button.textContent = "…";
    button.setAttribute("aria-label", "Loading dad joke");
    try {
      const response = await fetch("https://app.thonbecker.biz/api/joke");
      if (!response.ok) throw new Error("Dad joke unavailable");
      const source = (await response.text()).trim();
      if (!source) throw new Error("Dad joke unavailable");
      audio.src = source;
      await audio.play();
      button.textContent = "🔊";
      button.setAttribute("aria-label", "Stop dad joke");
    } catch (error) {
      button.textContent = "😂";
      button.setAttribute("aria-label", "Dad joke unavailable. Try again.");
    } finally {
      button.disabled = false;
    }
  });

  const reset = () => {
    button.textContent = "😂";
    button.setAttribute("aria-label", "Play dad joke of the day");
  };
  audio.addEventListener("ended", reset);
  audio.addEventListener("error", () => {
    if (button.disabled) return;
    button.textContent = "😂";
    button.setAttribute("aria-label", "Dad joke unavailable. Try again.");
  });
})();
