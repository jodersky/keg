(() => {
  const WS_URL =
    (location.protocol === "https:" ? "wss://" : "ws://") +
    location.host +
    "/__dev_ws";

  // ---------------- config ----------------
  const MIN_DELAY = 500;
  const MAX_DELAY = 10_000;
  const STABLE_CONNECTION_MS = 2_000;

  // ---------------- state ----------------
  let socket = null;
  let retryDelay = MIN_DELAY;
  let reconnectTimer = null;
  let stableTimer = null;

  let lastServerInstance = null;
  let isStable = false;
  let wasEverConnected = false;
  let isVisible = document.visibilityState === "visible";

  // ---------------- overlay ----------------
  const overlay = createOverlay();

  function setOverlay(state, text) {
    overlay.dataset.state = state;
    overlay.textContent = text;
    overlay.style.display = "block";
  }

  function hideOverlay() {
    overlay.style.display = "none";
  }

  // ---------------- logging ----------------
  function log(...args) {
    console.log("[dev-reload]", ...args);
  }

  // ---------------- connection ----------------
  function connect() {
    if (!isVisible) {
      log("tab hidden → delaying connection");
      return;
    }

    clearTimeout(reconnectTimer);
    log("connecting…");
    setOverlay("connecting", "Connecting to dev server…");

    socket = new WebSocket(WS_URL);

    socket.addEventListener("open", onOpen);
    socket.addEventListener("message", onMessage);
    socket.addEventListener("close", onClose);
    socket.addEventListener("error", onError);
  }

  function onOpen() {
    log("connected");
    wasEverConnected = true;
    retryDelay = MIN_DELAY;

    setOverlay("connected", "Connected to dev server");

    clearTimeout(stableTimer);
    stableTimer = setTimeout(() => {
      isStable = true;
      hideOverlay();
    }, STABLE_CONNECTION_MS);
  }

  function onMessage(event) {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch {
      return;
    }

    switch (msg.type) {
      case "hello": {
        const instanceId = msg.instance;
        log(`hello from server ${instanceId}`);

        if (
          lastServerInstance &&
          lastServerInstance !== instanceId
        ) {
          log("server restarted → reload");
          location.reload();
          return;
        }

        lastServerInstance = instanceId;
        break;
      }

      case "reload":
        log("reload requested");
        location.reload();
        break;
    }
  }

  function onClose() {
    log("connection closed");
    cleanup();
    scheduleReconnect();
  }

  function onError() {
    socket.close();
  }

  function cleanup() {
    clearTimeout(stableTimer);
    isStable = false;
  }

  // ---------------- reconnection ----------------
  function scheduleReconnect() {
    if (!isVisible) {
      log("tab hidden → waiting to reconnect");
      setOverlay("paused", "Dev server disconnected (tab hidden)");
      return;
    }

    const delay =
      retryDelay + Math.random() * 0.3 * retryDelay;

    log(`reconnecting in ${Math.round(delay)}ms`);
    setOverlay(
      "reconnecting",
      `Dev server disconnected — retrying in ${Math.round(delay)}ms`
    );

    reconnectTimer = setTimeout(connect, delay);

    retryDelay = Math.min(
      retryDelay * 2,
      MAX_DELAY
    );
  }

  // ---------------- visibility handling ----------------
  document.addEventListener("visibilitychange", () => {
    isVisible = document.visibilityState === "visible";

    if (isVisible) {
      log("tab visible → reconnect now");
      retryDelay = MIN_DELAY;
      connect();
    } else {
      log("tab hidden → pausing reconnects");
    }
  });

  // ---------------- overlay creation ----------------
  function createOverlay() {
    const el = document.createElement("div");
    el.id = "__dev_reload_overlay";
    el.style.cssText = `
      position: fixed;
      bottom: 12px;
      right: 12px;
      z-index: 99999;
      font-family: system-ui, sans-serif;
      font-size: 12px;
      padding: 8px 12px;
      border-radius: 6px;
      background: #111;
      color: #fff;
      box-shadow: 0 4px 16px rgba(0,0,0,0.25);
      opacity: 0.9;
      display: none;
      transition: opacity 0.2s ease;
    `;

    document.documentElement.appendChild(el);
    return el;
  }

  // ---------------- start ----------------
  connect();
})();
