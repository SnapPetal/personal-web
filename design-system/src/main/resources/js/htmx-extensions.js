htmx.registerExtension("loading-states", {
  htmx_before_request(elt) {
    if (elt.tagName === "FORM" || elt.tagName === "BUTTON") {
      elt.classList.add("processing");
    }
  },
  htmx_after_request(elt, detail) {
    if (elt.tagName === "FORM" || elt.tagName === "BUTTON") {
      elt.classList.remove("processing");
      if (elt.tagName === "FORM" && detail.ctx.response?.ok) {
        const modal = elt.closest(".modal");
        if (modal) {
          const modalInstance = bootstrap.Modal.getInstance(modal);
          if (modalInstance) {
            modalInstance.hide();
          }
        }
      }
    }
  },
});

htmx.registerExtension("form-validation", {
  htmx_before_request(elt) {
    if (elt.tagName === "FORM" && !elt.checkValidity()) {
      Array.from(elt.elements).forEach((input) => {
        if (!input.validity.valid) {
          input.classList.add("is-invalid");
        }
      });
      return false;
    }
  },
  htmx_after_request(elt) {
    if (elt.tagName === "FORM") {
      Array.from(elt.elements).forEach((input) => {
        input.classList.remove("is-invalid");
      });
    }
  },
});

function markHtmxError(elt) {
  elt.classList.add("htmx-error");
  setTimeout(() => {
    elt.classList.remove("htmx-error");
  }, 500);
}

htmx.registerExtension("error-handling", {
  htmx_error(elt) {
    markHtmxError(elt);
  },
  htmx_response_error(elt) {
    markHtmxError(elt);
  },
});

document.addEventListener("DOMContentLoaded", () => {
  document.body.addEventListener("htmx:response:error", (event) => {
    const status = event.detail.ctx?.response?.status ?? "unknown";
    const path = event.detail.ctx?.request?.action ?? "unknown";
    console.error(`HTMX request failed with status ${status}`, path);
    if (window.posthog) {
      window.posthog.capture("htmx_request_error", {
        error_type: "response_error",
        status_code: status,
        request_path: path,
      });
    }
  });

  document.body.addEventListener("htmx:error", (event) => {
    console.error("HTMX request could not be sent", event.detail.ctx?.error);
    if (window.posthog) {
      window.posthog.capture("htmx_request_error", {
        error_type: "send_error",
      });
    }
  });
});
