(() => {
  function headerControls() {
    return {
      menu: document.querySelector("[data-menu-toggle]"),
      navigation: document.querySelector("#primary-navigation"),
    };
  }

  function closeMenu(menu, navigation) {
    navigation.classList.remove("is-open");
    menu.setAttribute("aria-expanded", "false");
    menu.setAttribute("aria-label", "Open navigation");
  }

  document.addEventListener("click", (event) => {
    const { menu, navigation } = headerControls();
    if (!menu || !navigation) {
      return;
    }
    if (menu.contains(event.target)) {
      const open = navigation.classList.toggle("is-open");
      menu.setAttribute("aria-expanded", String(open));
      menu.setAttribute(
        "aria-label",
        open ? "Close navigation" : "Open navigation"
      );
      return;
    }
    if (navigation.contains(event.target)) {
      if (event.target.closest("a")) {
        closeMenu(menu, navigation);
      }
      return;
    }
    closeMenu(menu, navigation);
  });

  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") {
      return;
    }
    const { menu, navigation } = headerControls();
    if (!menu || !navigation) {
      return;
    }
    closeMenu(menu, navigation);
    menu.focus();
  });
})();
