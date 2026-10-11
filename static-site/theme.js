(() => {
  addEventListener("DOMContentLoaded", () => {
    const menu = document.querySelector("[data-menu-toggle]");
    const navigation = document.querySelector("#primary-navigation");
    if (menu && navigation) {
      const closeMenu = () => {
        navigation.classList.remove("is-open");
        menu.setAttribute("aria-expanded", "false");
        menu.setAttribute("aria-label", "Open navigation");
      };
      menu.addEventListener("click", () => {
        const open = navigation.classList.toggle("is-open");
        menu.setAttribute("aria-expanded", String(open));
        menu.setAttribute("aria-label", open ? "Close navigation" : "Open navigation");
      });
      navigation.addEventListener("click", (event) => {
        if (event.target.closest("a")) {
          closeMenu();
        }
      });
      document.addEventListener("click", (event) => {
        if (!navigation.contains(event.target) && !menu.contains(event.target)) {
          closeMenu();
        }
      });
      document.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
          closeMenu();
          menu.focus();
        }
      });
    }
    document.addEventListener("click", (event) => {
      const toggle = event.target.closest(".verse-toggle");
      if (!toggle) return;
      const text = document.querySelector(".verse-text");
      const greek = text.textContent === toggle.dataset.english;
      text.textContent = greek ? toggle.dataset.greek : toggle.dataset.english;
      text.classList.toggle("greek", greek);
      if (greek) {
        text.setAttribute("lang", "grc");
      } else {
        text.removeAttribute("lang");
      }
      toggle.textContent = greek ? "Show English" : "Show Greek";
    });
  });
})();
