document.addEventListener("alpine:init", () => {
  Alpine.data("budgetChat", () => ({
    conversationId: "",
    message: "",
    files: [],
    messages: [],
    loading: false,
    error: "",

    renderMarkdown(content) {
      return DOMPurify.sanitize(
        marked.parse(content, { breaks: true, gfm: true })
      );
    },

    async init() {
      const root = document.getElementById("budget-chat");
      const initialId = root?.dataset.initialConversationId;
      const storedId = localStorage.getItem("personalweb-budget-conversation");
      this.conversationId =
        storedId && this.isUuid(storedId) ? storedId : initialId;
      if (!this.isUuid(this.conversationId)) {
        this.error =
          "Could not start a conversation. Reload the page and try again.";
        return;
      }

      localStorage.setItem(
        "personalweb-budget-conversation",
        this.conversationId
      );
      await this.loadConversation();
    },

    async loadConversation() {
      try {
        const response = await fetch(
          `/budget/conversation?conversationId=${encodeURIComponent(
            this.conversationId
          )}`,
          { credentials: "same-origin", cache: "no-store" }
        );
        if (response.status === 401 || response.redirected) {
          window.location.assign("/auth/login?redirect=%2Fbudget");
          return;
        }

        const result = await response.json();
        if (!response.ok || result.error) {
          this.error = result.error || "Could not load this conversation.";
          return;
        }
        this.messages = result.messages;
        this.scrollToLatest();
      } catch {
        this.error =
          "Could not load this conversation. Check your connection and try again.";
      }
    },

    async send() {
      const question = this.message.trim();
      if (!question || this.loading) {
        return;
      }

      if (this.files.length > 3) {
        this.error = "Attach no more than 3 files.";
        return;
      }

      this.loading = true;
      this.error = "";
      const formData = new FormData();
      formData.set("message", question);
      formData.set("conversationId", this.conversationId);
      this.files.forEach((file) => formData.append("files", file));

      const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
      const csrfHeader = document.querySelector(
        'meta[name="_csrf_header"]'
      )?.content;
      const headers = {};
      if (csrfToken && csrfHeader) {
        headers[csrfHeader] = csrfToken;
      }

      try {
        const response = await fetch("/budget/chat", {
          method: "POST",
          body: formData,
          credentials: "same-origin",
          headers,
        });
        if (
          response.redirected &&
          new URL(response.url).pathname === "/auth/login"
        ) {
          window.location.assign("/auth/login?redirect=%2Fbudget");
          return;
        }

        const result = await response.json();
        if (!response.ok || result.error) {
          this.error =
            result.error ||
            "Budget Helper is temporarily unavailable. Please try again.";
          return;
        }

        this.messages.push(
          { role: "user", content: question },
          { role: "assistant", content: result.answer }
        );
        this.message = "";
        this.files = [];
        document.getElementById("budget-files").value = "";
        this.scrollToLatest();
      } catch {
        this.error =
          "Could not reach Budget Helper. Check your connection and try again.";
      } finally {
        this.loading = false;
      }
    },

    async newConversation() {
      if (this.loading) {
        return;
      }
      this.loading = true;
      this.error = "";
      const formData = new FormData();
      formData.set("conversationId", this.conversationId);
      const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
      const csrfHeader = document.querySelector(
        'meta[name="_csrf_header"]'
      )?.content;
      const headers = {};
      if (csrfToken && csrfHeader) {
        headers[csrfHeader] = csrfToken;
      }

      try {
        const response = await fetch("/budget/conversation/reset", {
          method: "POST",
          body: formData,
          credentials: "same-origin",
          headers,
        });
        if (
          response.redirected &&
          new URL(response.url).pathname === "/auth/login"
        ) {
          window.location.assign("/auth/login?redirect=%2Fbudget");
          return;
        }

        const result = await response.json();
        if (
          !response.ok ||
          result.error ||
          !this.isUuid(result.conversationId)
        ) {
          this.error = result.error || "Could not start a new conversation.";
          return;
        }

        this.conversationId = result.conversationId;
        localStorage.setItem(
          "personalweb-budget-conversation",
          this.conversationId
        );
        this.messages = [];
        this.message = "";
        this.files = [];
        document.getElementById("budget-files").value = "";
      } catch {
        this.error =
          "Could not start a new conversation. Check your connection and try again.";
      } finally {
        this.loading = false;
      }
    },

    isUuid(value) {
      return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
        value || ""
      );
    },

    scrollToLatest() {
      this.$nextTick(() => {
        const container = document.getElementById("budget-chat-messages");
        if (container) {
          container.scrollTop = container.scrollHeight;
        }
      });
    },
  }));
});
