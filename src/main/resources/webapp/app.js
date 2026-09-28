let userCsrf = "";
const LOADING_TEXTS = [
  "Bribing the GitLab servers...",
  "Waking up the paper plane...",
  "Untangling webhook spaghetti...",
  "Asking Telegram nicely...",
  "Counting merge requests...",
  "Herding notifications...",
  "Feeding the CI pipeline...",
  "Pretending to be fast...",
  "Teaching the bot new tricks...",
  "Blaming the intern...",
  "Reticulating splines...",
  "Negotiating with the database...",
  "Almost there (probably)...",
  "Checking if production is on fire...",
  "Locating the missing semicolon...",
  "Convincing CI to cooperate...",
  "Reading the docs so you do not have to...",
  "Converting caffeine into webhooks...",
  "Wiggling the Ethernet cable...",
  "Refactoring the universe...",
  "Buying more RAM...",
  "Polishing the paper plane...",
  "Rebasing reality...",
  "Hunting flaky tests...",
  "Aligning the merge request chakras...",
  "Optimizing optimizations...",
  "Compiling feelings...",
  "Assembling pixels...",
  "Sharpening the admin badge...",
  "Checking the hidden group scope...",
  "Making Telegram behave...",
  "Summoning the webhook spirits...",
  "Dusting off the database indexes...",
  "Resolving merge conflicts diplomatically...",
  "Interpreting GitLab hieroglyphics...",
  "Debugging the inevitable edge case...",
  "Reheating last nights deployment...",
  "Rolling for initiative against latency...",
  "Translating stack traces...",
  "Asking the bot who broke production...",
  "Preparing a strongly worded callback query...",
  "Generating plausible progress...",
  "Performing advanced button science...",
  "Double-checking the secret secrets...",
  "Shuffling repositories into formation...",
  "Reassuring the load balancer...",
  "Charging the rubber duck...",
  "Looking busy for the status page...",
  "Removing one more legacy workaround...",
  "Rehydrating dehydrated JSON...",
  "Inspecting suspiciously round trip times...",
  "Doing cloud things with tiny paper planes...",
];
let loadingTimer = null;
(function () {
  const el = document.getElementById("loadingText");
  if (!el) return;
  let last = -1;
  function nextLoadingText() {
    if (LOADING_TEXTS.length === 1) return LOADING_TEXTS[0];
    let i = Math.floor(Math.random() * LOADING_TEXTS.length);
    while (i === last) i = Math.floor(Math.random() * LOADING_TEXTS.length);
    last = i;
    return LOADING_TEXTS[i];
  }
  el.innerText = nextLoadingText();
  loadingTimer = setInterval(function () {
    el.innerText = nextLoadingText();
  }, 2200);
})();
(function initLottie() {
  const container = document.getElementById("lottieContainer");
  if (!container || typeof lottie === "undefined") return;
  try {
    lottie.loadAnimation({
      container: container,
      renderer: "svg",
      loop: true,
      autoplay: true,
      path: "{{BASE_PATH}}/webapp/loading.json",
    });
  } catch (e) {}
})();
let sToken = "";
let currentContext = {};
let items = [];
let currentItem = null;
let listMode = "scoped";
const avatars = [
  "01-wildlife-avatar-1-giraffe.png",
  "02-wildlife-avatar-10-tiger.png",
  "03-wildlife-avatar-11-zebra.png",
  "04-wildlife-avatar-12-sloth.png",
  "05-wildlife-avatar-13-yak.png",
  "06-wildlife-avatar-14-impala.png",
  "07-wildlife-avatar-2-elephant.png",
  "08-wildlife-avatar-3-lion.png",
  "09-wildlife-avatar-4-hippo.png",
  "10-wildlife-avatar-5-koala.png",
  "11-wildlife-avatar-6-deer.png",
  "12-wildlife-avatar-7-bear.png",
  "13-wildlife-avatar-8-mouse.png",
  "14-wildlife-avatar-9-rabbit.png",
  "15-animal-avatar-1-giraffe.png",
  "16-animal-avatar-2-panda.png",
  "17-animal-avatar-3-lion.png",
  "18-animal-avatar-4-elephant.png",
  "19-animal-avatar-5-hippo.png",
  "20-animal-avatar-6-tiger.png",
  "21-animal-avatar-7-bear.png",
  "22-baby-jungle-avatar-1-koala.png",
  "23-baby-jungle-avatar-2-giraffe.png",
  "24-baby-jungle-avatar-3-elephant.png",
  "25-baby-jungle-avatar-4-leopard.png",
  "26-baby-jungle-avatar-5-lion.png",
  "27-baby-jungle-avatar-6-bear.png",
  "28-cat-avatar-1.png",
  "29-cat-avatar-2.png",
  "30-cat-avatar-3.png",
  "31-cat-avatar-4.png",
  "32-cat-avatar-5.png",
  "33-doodle-cat-avatar-1.png",
  "34-doodle-cat-avatar-2.png",
  "35-doodle-cat-avatar-3.png",
  "36-doodle-cat-avatar-4.png",
];

function getAuthHeaders(extra) {
  const h = Object.assign({}, extra || {});
  if (userCsrf) h["X-CSRF-Token"] = userCsrf;
  if (sToken) h["X-Session-" + "Token"] = sToken;
  return h;
}

function copyValue(val, el) {
  if (navigator.clipboard) navigator.clipboard.writeText(val);
  if (el.getAttribute("data-copying") === "true") return;
  el.setAttribute("data-copying", "true");
  const originalHtml = el.innerHTML;
  const originalColor = el.style.color;
  el.innerText = "✓ Copied";
  el.style.color = "var(--success)";
  if (el._copyTimer) clearTimeout(el._copyTimer);
  el._copyTimer = setTimeout(function () {
    el.innerHTML = originalHtml;
    el.style.color = originalColor;
    el.removeAttribute("data-copying");
    delete el._copyTimer;
  }, 1800);
}

function escapeHtml(value) {
  return String(value == null ? "" : value).replace(/[&<>"']/g, function (c) {
    return {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    }[c];
  });
}

function destinationLabel(item) {
  if (item.chatName && item.chatName.trim()) return item.chatName;
  return (
    "Chat #" +
    item.telegramChatId +
    (item.telegramTopicId ? " / Topic " + item.telegramTopicId : "")
  );
}

function destinationMeta(item) {
  return (
    "Chat " +
    item.telegramChatId +
    (item.telegramTopicId ? " · Topic " + item.telegramTopicId : "")
  );
}

function repoMeta(item) {
  const host = item.gitlabBaseUrl.replace(/^https?:\/\//, "");
  return (
    host +
    (item.gitlabProjectId ? "/#" + item.gitlabProjectId : "") +
    " · id " +
    item.id.substring(0, 8)
  );
}

function avatarFor(item, index) {
  let n = 0;
  for (let i = 0; i < item.id.length; i++)
    n = (n + item.id.charCodeAt(i)) % avatars.length;
  return avatars[(n + index) % avatars.length];
}

function fallbackAvatarFor(item, index) {
  return avatarFor(item, index + 1);
}

function showScreen(name) {
  if (name !== "loading" && loadingTimer) {
    clearInterval(loadingTimer);
    loadingTimer = null;
  }
  document.querySelectorAll(".panel").forEach(function (el) {
    el.classList.remove("active");
  });
  const target = document.getElementById("screen-" + name);
  if (target) target.classList.add("active");
}

function extractStartParam() {
  const tg = window.Telegram && window.Telegram.WebApp;
  if (tg && tg.initDataUnsafe && tg.initDataUnsafe.start_param)
    return tg.initDataUnsafe.start_param;
  const q = new URLSearchParams(window.location.search);
  return (
    q.get("startapp") ||
    q.get("tgWebAppStartParam") ||
    q.get("start_param") ||
    ""
  );
}

async function initWebApp() {
  const tg = window.Telegram && window.Telegram.WebApp;
  if (tg) {
    tg.ready();
    tg.expand();
  }
  setupHandlers();
  if (window.location.protocol === "file:") {
    loadInstallations();
    return;
  }
  showScreen("loading");
  try {
    const res = await fetch("{{BASE_PATH}}/api/webapp/auth", {
      method: "POST",
      headers: getAuthHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({
        initData: (tg && tg.initData) || "",
        startParam: extractStartParam(),
      }),
    });
    if (!res.ok) {
      if (res.status === 400 || res.status === 401 || !tg || !tg.initData) {
        renderError(
          "Telegram Access Required",
          "This management portal must be opened inside Telegram.\nOpen @{{BOT_USERNAME}} and tap OPEN.",
        );
      } else {
        renderError(
          res.status === 403
            ? "Admin access required"
            : "Authentication required",
          "Only Telegram group admins can manage repositories here.",
        );
      }
      return;
    }
    const data = await res.json();
    userCsrf = data.csrf;
    if (data.sessionToken) sToken = data.sessionToken;
    currentContext = {
      chatId: data.telegramChatId,
      topicId: data.telegramTopicId,
    };
    listMode =
      currentContext.chatId != null && currentContext.chatId < 0
        ? "scoped"
        : "all";
    await loadInstallations();
  } catch (e) {
    renderError(
      "Connection error",
      "Could not reach Nuecagram. Check your connection and try again.",
    );
  }
}

async function loadInstallations(silent) {
  if (window.location.protocol === "file:") {
    items = [
      {
        id: "1b179442-8888-4444-9999-1234567890ab",
        repoName: "nuecagram",
        chatName: "Android Team / Notifications",
        gitlabBaseUrl: "https://gitlab.com",
        gitlabProjectId: 381,
        telegramChatId: -100123456789,
        telegramTopicId: 2,
        muted: false,
      },
      {
        id: "2c289553-9999-5555-0000-2345678901bc",
        repoName: "mobile-app",
        chatName: "iOS Team / Notifications",
        gitlabBaseUrl: "https://gitlab.com",
        gitlabProjectId: 482,
        telegramChatId: -100987654321,
        telegramTopicId: null,
        muted: true,
      },
    ];
    renderList();
    return;
  }
  if (!silent) {
    const el = document.getElementById("installationsList");
    if (el) el.innerHTML = "";
  }
  try {
    const qs = listMode === "all" ? "?scope=all" : "";
    const res = await fetch("{{BASE_PATH}}/api/webapp/installations" + qs, {
      headers: getAuthHeaders(),
    });
    if (!res.ok) {
      if (!silent) {
        const error = await res.json().catch(function () {
          return null;
        });
        const message =
          error && error.error
            ? error.error
            : "Could not load installations. Please try again.";
        renderError("Setup unavailable", message);
      }
      return;
    }
    items = await res.json();
    renderList();
  } catch (e) {
    if (!silent)
      renderError(
        "Connection error",
        "Could not load repositories. Check your connection and try again.",
      );
  }
}

function renderHeader() {
  const scoped =
    listMode !== "all" &&
    currentContext.chatId != null &&
    currentContext.chatId < 0;
  document.getElementById("listTitle").innerText = scoped
    ? "Repositories"
    : "All repositories";
  document.getElementById("listSubtitle").innerText = scoped
    ? destinationMeta({
        telegramChatId: currentContext.chatId,
        telegramTopicId: currentContext.topicId,
      }) + "\nNotifications go to this group/topic."
    : "Repositories you can manage across groups/topics.";
  document.getElementById("btnAll").style.display = scoped ? "" : "none";
  const topActions = document.querySelector("#screen-list .top-actions");
  if (topActions) topActions.style.display = "flex";
}

function renderList() {
  renderHeader();
  showScreen("list");
  const el = document.getElementById("installationsList");
  if (!items.length) {
    const scoped =
      listMode !== "all" &&
      currentContext.chatId != null &&
      currentContext.chatId < 0;
    el.innerHTML = scoped
      ? '<div class="box"><h1>No repositories here yet.</h1><p>Add a GitLab project to start notifications.</p><button class="primary" id="btnEmptyAdd">+ Add repository</button></div>'
      : '<div class="box"><h1>No repositories found.</h1><p>Tap + Add repository to connect a project to one of your Telegram groups.</p></div>';
    const add = document.getElementById("btnEmptyAdd");
    if (add) add.addEventListener("click", openAdd);
    return;
  }
  el.innerHTML = "";
  items.forEach(function (item, index) {
    const card = document.createElement("button");
    card.className = "card" + (item.muted ? " muted" : "");
    card.innerHTML =
      '<img class="avatar" alt="" aria-hidden="true" src="{{BASE_PATH}}/webapp/avatars/' +
      avatarFor(item, index) +
      '" onerror="this.onerror=null;this.src=\'{{BASE_PATH}}/webapp/avatars/' +
      fallbackAvatarFor(item, index) +
      "'\">" +
      '<div class="grow"><div class="row"><div class="title">' +
      escapeHtml(item.repoName) +
      '</div><span class="badge ' +
      (item.muted ? 'badge-muted">MUTED' : 'badge-active">ACTIVE') +
      '</span><span class="chev">›</span></div>' +
      '<div class="sub">' +
      escapeHtml(destinationLabel(item)) +
      '</div><div class="meta">' +
      escapeHtml(repoMeta(item)) +
      "</div></div>";
    card.addEventListener("click", function () {
      openDetail(item.id);
    });
    el.appendChild(card);
  });
}

function renderError(title, body) {
  showScreen("list");
  document.getElementById("listTitle").innerText = title;
  document.getElementById("listSubtitle").innerText = body;
  const topActions = document.querySelector("#screen-list .top-actions");
  if (topActions) topActions.style.display = "none";
  const el = document.getElementById("installationsList");
  if (title === "Telegram Access Required") {
    el.innerHTML =
      '<div class="box" style="text-align:center;padding:28px 18px;">' +
      '<h1 style="font-size:20px;margin-bottom:10px;">Telegram Access Required</h1>' +
      '<p style="margin-bottom:20px;">This management portal must be opened inside Telegram.</p>' +
      '<div class="codebox" style="margin-bottom:18px;font-weight:700;">Open @{{BOT_USERNAME}} and tap OPEN</div>' +
      '<a href="https://t.me/{{BOT_USERNAME}}" class="primary" style="display:block;text-decoration:none;padding:12px;border-radius:12px;text-align:center;" target="_blank" rel="noopener">Open Telegram Bot</a></div>';
  } else {
    el.innerHTML = '<div class="box"><p>' + escapeHtml(body) + "</p></div>";
  }
}

async function openDetail(id) {
  const existing = items.find(function (x) {
    return x.id === id;
  });
  if (existing) {
    currentItem = existing;
    renderDetail();
    showScreen("detail");
  }
  try {
    const res = await fetch("{{BASE_PATH}}/api/webapp/installations/" + id, {
      headers: getAuthHeaders(),
    });
    if (!res.ok) {
      if (!existing) {
        const title =
          res.status === 404
            ? "Repository not found"
            : res.status === 401 || res.status === 403
              ? "Access denied"
              : "Could not open repository";
        const body =
          res.status === 404
            ? "This repository is no longer available or you no longer have access."
            : "Try again in a moment or return to the list.";
        renderError(title, body);
      }
      return;
    }
    currentItem = await res.json();
    items = items.map(function (x) {
      return x.id === currentItem.id ? currentItem : x;
    });
    renderDetail();
    if (!existing) showScreen("detail");
  } catch (e) {
    if (!existing)
      renderError(
        "Connection error",
        "Could not load repository details. Check your connection and try again.",
      );
  }
}

function renderDetail() {
  const item = currentItem;
  const gitlabPermalink =
    item.gitlabBaseUrl.replace(/\/+$/, "") +
    (item.gitlabProjectId ? "/projects/" + item.gitlabProjectId : "");
  document.getElementById("detailBody").innerHTML =
    '<div class="card"><img class="avatar" alt="" aria-hidden="true" src="{{BASE_PATH}}/webapp/avatars/' +
    avatarFor(item, 0) +
    '" onerror="this.onerror=null;this.src=\'{{BASE_PATH}}/webapp/avatars/' +
    fallbackAvatarFor(item, 0) +
    '\'"><div class="grow"><div class="title">' +
    escapeHtml(item.repoName) +
    '</div><div class="sub">' +
    escapeHtml(destinationLabel(item)) +
    '</div></div><span class="badge ' +
    (item.muted ? 'badge-muted">MUTED' : 'badge-active">ACTIVE') +
    "</span></div>" +
    '<div class="section"><div class="section-title">Repository</div><div class="group"><div class="row" style="cursor:pointer" onclick="copyValue(\'' +
    escapeHtml(gitlabPermalink) +
    '\', this.querySelector(\'.meta\'))"><strong>GitLab</strong><div style="display:flex;align-items:center;gap:6px;min-width:0;"><span class="meta">' +
    escapeHtml(gitlabPermalink) +
    '</span><a href="' +
    escapeHtml(gitlabPermalink) +
    '" target="_blank" rel="noopener" style="color:var(--button);font-size:16px;font-weight:700;text-decoration:none;padding:2px 6px;border-radius:6px;background:var(--input-bg);flex-shrink:0;" onclick="event.stopPropagation();" aria-label="Open GitLab">↗</a></div></div><div class="row" style="cursor:pointer" onclick="copyValue(\'' +
    escapeHtml(item.id) +
    "', this.querySelector('.meta'))\"><strong>Installation ID</strong><span class=\"meta\">" +
    escapeHtml(item.id) +
    "</span></div></div></div>" +
    '<div class="section"><div class="section-title">Destination</div><div class="group"><div class="row"><div class="grow"><strong>Telegram</strong><div class="sub" style="font-weight:700;margin-top:2px;">' +
    escapeHtml(destinationLabel(item)) +
    '</div><div class="meta" style="margin-top:2px;">' +
    escapeHtml(destinationMeta(item)) +
    "</div></div></div></div></div>" +
    '<div class="section"><div class="section-title">Actions</div><div class="split"><button id="btnTest">Test notification</button><button id="btnMute">' +
    (item.muted ? "Unmute notifications" : "Mute notifications") +
    '</button></div><div id="actionHelp" class="helper"></div></div>' +
    '<div class="section"><div class="section-title">Settings</div><button id="btnEdit">Edit names ›</button></div><div class="section"><div class="section-title">Danger zone</div><div style="display:flex;flex-direction:column;gap:10px;"><button id="btnRotate" class="danger">Rotate webhook token ›</button><button id="btnDelete" class="danger">Delete repository ›</button></div></div>';
  document.getElementById("btnTest").addEventListener("click", testDelivery);
  document.getElementById("btnMute").addEventListener("click", toggleMute);
  document.getElementById("btnEdit").addEventListener("click", openEdit);
  document
    .getElementById("btnRotate")
    .addEventListener("click", openRotateConfirm);
  document
    .getElementById("btnDelete")
    .addEventListener("click", openDeleteConfirm);
}

function setAction(text, ok) {
  const el = document.getElementById("actionHelp");
  el.className = "helper " + (ok ? "ok" : "err");
  el.innerText = text;
  setTimeout(function () {
    el.innerText = "";
  }, 2500);
}

function openEdit() {
  document.getElementById("editRepoName").value = currentItem.repoName || "";
  document.getElementById("editChatName").value = currentItem.chatName || "";
  document.getElementById("editErr").innerText = "";
  showScreen("edit");
}

async function saveIdentity() {
  const repoName = document.getElementById("editRepoName").value.trim();
  const chatName = document.getElementById("editChatName").value.trim();
  if (!repoName) {
    document.getElementById("editErr").innerText = "Repository name required.";
    return;
  }
  try {
    const res = await fetch(
      "{{BASE_PATH}}/api/webapp/installations/" + currentItem.id + "/identity",
      {
        method: "POST",
        headers: getAuthHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify({ repoName: repoName, chatName: chatName }),
      },
    );
    if (!res.ok) {
      document.getElementById("editErr").innerText = "Could not save names.";
      return;
    }
    currentItem = await res.json();
    items = items.map(function (x) {
      return x.id === currentItem.id ? currentItem : x;
    });
    renderDetail();
    showScreen("detail");
  } catch (e) {
    document.getElementById("editErr").innerText = "Could not save names.";
  }
}

let cachedDestinations = null;
let addRequestId = 0;

function renderDestinationOptions(dests) {
  const selectEl = document.getElementById("inDestination");
  if (!selectEl) return;
  if (dests && dests.length > 0) {
    selectEl.innerHTML = dests
      .map(function (d) {
        return (
          '<option value="' +
          escapeHtml(d.id) +
          '">' +
          escapeHtml(d.name) +
          "</option>"
        );
      })
      .join("");
    selectEl.disabled = false;
    selectEl.selectedIndex = 0;
    updateChatNameFromDestination();
  } else {
    selectEl.innerHTML =
      '<option value="">No eligible groups. Add Nuecagram as an administrator in a group where you are a member.</option>';
    selectEl.disabled = false;
  }
}

function updateChatNameFromDestination() {
  const selectEl = document.getElementById("inDestination");
  const chatNameEl = document.getElementById("inChatName");
  if (!selectEl || !chatNameEl) return;
  const idx = selectEl.selectedIndex;
  if (idx < 0 || !selectEl.options[idx]) return;
  const selectedText = selectEl.options[idx].text;
  if (
    selectedText &&
    selectedText.indexOf("Loading") === -1 &&
    selectedText.indexOf("No Telegram groups") === -1 &&
    selectedText.indexOf("No eligible groups") === -1 &&
    selectedText.indexOf("Could not load") === -1
  ) {
    if (
      !chatNameEl.value ||
      chatNameEl.getAttribute("data-autofilled") === "true"
    ) {
      chatNameEl.value = selectedText;
      chatNameEl.setAttribute("data-autofilled", "true");
    }
  }
}

async function openAdd() {
  const isGroup = currentContext.chatId != null && currentContext.chatId < 0;
  const selectEl = document.getElementById("inDestination");
  const requestId = ++addRequestId;

  const groupMatch = isGroup
    ? items.find(function (x) {
        return x.telegramChatId === currentContext.chatId;
      })
    : null;
  const groupNameText = isGroup
    ? groupMatch && groupMatch.chatName
      ? groupMatch.chatName
      : destinationMeta({
          telegramChatId: currentContext.chatId,
          telegramTopicId: currentContext.topicId,
        })
    : "Target Telegram Destination";

  document.getElementById("createDestinationName").innerText = groupNameText;
  document.getElementById("createDestinationMeta").innerHTML = isGroup
    ? destinationMeta({
        telegramChatId: currentContext.chatId,
        telegramTopicId: currentContext.topicId,
      })
    : "Select a destination group or topic<br>below to connect your GitLab project.";
  document.getElementById("wizErr").innerText = "";
  const inChatName = document.getElementById("inChatName");
  if (inChatName) {
    inChatName.value = "";
    inChatName.setAttribute("data-autofilled", "true");
  }

  showScreen("wizard");

  if (isGroup) {
    document.getElementById("fieldDestination").style.display = "none";
  } else {
    document.getElementById("fieldDestination").style.display = "";
    if (cachedDestinations) {
      renderDestinationOptions(cachedDestinations);
    } else {
      selectEl.innerHTML = '<option value="">Loading destinations...</option>';
      selectEl.disabled = true;
    }
    try {
      const res = await fetch("{{BASE_PATH}}/api/webapp/destinations", {
        headers: getAuthHeaders(),
      });
      if (requestId !== addRequestId) return;
      if (res.ok) {
        const dests = await res.json();
        if (requestId !== addRequestId) return;
        cachedDestinations = dests;
        renderDestinationOptions(dests);
      } else {
        const error = await res.json().catch(function () {
          return null;
        });
        if (!cachedDestinations) {
          selectEl.innerHTML =
            '<option value="">Could not load destinations: ' +
            escapeHtml(
              error && error.error ? error.error : "please try again.",
            ) +
            "</option>";
          selectEl.disabled = false;
        }
      }
    } catch (e) {
      if (requestId !== addRequestId) return;
      if (!cachedDestinations) {
        selectEl.innerHTML =
          '<option value="">Could not load destinations</option>';
        selectEl.disabled = false;
      }
    }
  }
}

async function createInstallation() {
  document.getElementById("wizErr").innerText = "";
  const url = document.getElementById("inUrl").value.trim();
  const pid = parseInt(document.getElementById("inPid").value.trim(), 10);
  const repoName = document.getElementById("inRepoName").value.trim();
  const chatName = document.getElementById("inChatName").value.trim();
  if (!url.startsWith("https://")) {
    document.getElementById("wizErr").innerText =
      "GitLab URL must start with https://";
    return;
  }
  if (!pid || isNaN(pid)) {
    document.getElementById("wizErr").innerText =
      "Valid GitLab project ID is required.";
    return;
  }
  if (!repoName) {
    document.getElementById("wizErr").innerText =
      "Repository name is required.";
    return;
  }
  const payload = {
    repoName: repoName,
    chatName: chatName,
    gitlabBaseUrl: url,
    gitlabProjectId: pid,
  };
  if (currentContext.chatId != null && currentContext.chatId < 0) {
    payload.telegramChatId = currentContext.chatId;
    if (currentContext.topicId != null)
      payload.telegramTopicId = currentContext.topicId;
  } else {
    const destVal = document.getElementById("inDestination").value;
    if (!destVal) {
      document.getElementById("wizErr").innerText =
        "Target Telegram group destination required.";
      return;
    }
    const parts = destVal.split(":");
    const destChatId = parseInt(parts[0], 10);
    const destTopicId = parts.length > 1 ? parseInt(parts[1], 10) : 0;
    if (!destChatId || destChatId >= 0) {
      document.getElementById("wizErr").innerText =
        "Target Telegram group destination required.";
      return;
    }
    payload.telegramChatId = destChatId;
    if (destTopicId !== 0) payload.telegramTopicId = destTopicId;
  }
  try {
    const res = await fetch("{{BASE_PATH}}/api/webapp/installations", {
      method: "POST",
      headers: getAuthHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify(payload),
    });
    if (res.status !== 201) {
      const error = await res.json().catch(function () {
        return null;
      });
      document.getElementById("wizErr").innerText =
        error && error.error
          ? error.error
          : "Failed to create repository. Please try again.";
      return;
    }
    const data = await res.json();
    cachedDestinations = null;
    const gitlabProjectUrl =
      payload.gitlabBaseUrl.replace(/\/+$/, "") +
      (payload.gitlabProjectId ? "/projects/" + payload.gitlabProjectId : "");
    showReveal(
      data.credential,
      data.webhookUrl,
      "Repository created",
      false,
      gitlabProjectUrl,
    );
  } catch (e) {
    document.getElementById("wizErr").innerText =
      "Failed to connect to server. Please check your connection.";
  }
}

function openRotateConfirm() {
  document.getElementById("rotConfirmTitle").innerText =
    "Rotate token for " +
    (currentItem ? currentItem.repoName : "repository") +
    "?";
  document.getElementById("rotErr").innerText = "";
  const btn = document.getElementById("btnConfirmRotate");
  if (btn) btn.disabled = false;
  showScreen("rotate-confirm");
}

async function confirmRotateInstallation() {
  if (!currentItem) return;
  const btn = document.getElementById("btnConfirmRotate");
  if (btn && btn.disabled) return;
  if (btn) btn.disabled = true;
  try {
    const res = await fetch(
      "{{BASE_PATH}}/api/webapp/installations/" + currentItem.id + "/rotate",
      {
        method: "POST",
        headers: getAuthHeaders({ "Content-Type": "application/json" }),
      },
    );
    if (!res.ok) {
      document.getElementById("rotErr").innerText = "Could not rotate token.";
      if (btn) btn.disabled = false;
      return;
    }
    const data = await res.json();
    showReveal(data.credential, "", "Webhook token rotated", true);
  } catch (e) {
    document.getElementById("rotErr").innerText = "Could not rotate token.";
    if (btn) btn.disabled = false;
  }
}

function openDeleteConfirm() {
  document.getElementById("delConfirmTitle").innerText =
    "Delete " + (currentItem ? currentItem.repoName : "repository") + "?";
  document.getElementById("delErr").innerText = "";
  const btn = document.getElementById("btnConfirmDelete");
  if (btn) btn.disabled = false;
  showScreen("delete-confirm");
}

async function confirmDeleteInstallation() {
  if (!currentItem) return;
  const btn = document.getElementById("btnConfirmDelete");
  if (btn && btn.disabled) return;
  if (btn) btn.disabled = true;
  try {
    const res = await fetch(
      "{{BASE_PATH}}/api/webapp/installations/" + currentItem.id,
      {
        method: "DELETE",
        headers: getAuthHeaders({ "Content-Type": "application/json" }),
      },
    );
    if (!res.ok) {
      document.getElementById("delErr").innerText =
        "Could not delete repository.";
      if (btn) btn.disabled = false;
      return;
    }
    items = items.filter(function (x) {
      return x.id !== currentItem.id;
    });
    currentItem = null;
    cachedDestinations = null;
    showScreen("list");
    await loadInstallations();
  } catch (e) {
    document.getElementById("delErr").innerText =
      "Could not delete repository.";
    if (btn) btn.disabled = false;
  }
}

function showReveal(token, url, title, isRotate, gitlabHooksUrl) {
  document.getElementById("revTitle").innerText = title;
  const sub = document.getElementById("revSubtitle");
  if (sub) {
    sub.innerText = isRotate
      ? "Copy this rotated token now. It will only be shown once."
      : "Copy this webhook token now. It will only be shown once.";
  }
  const secret = document.getElementById("revSecret");
  secret.innerText = token;
  secret.classList.add("hidden");
  document.getElementById("btnReveal").innerText = "Reveal";
  const urlEl = document.getElementById("revUrl");
  if (urlEl) {
    const field = urlEl.closest(".field");
    if (field) field.style.display = isRotate ? "none" : "";
    urlEl.innerText = url || "";
  }
  const guideBox = document.getElementById("revGuideBox");
  if (guideBox) guideBox.style.display = isRotate ? "none" : "block";
  const hooksBtn = document.getElementById("btnRevGitlabHooks");
  if (hooksBtn) {
    if (gitlabHooksUrl) {
      hooksBtn.href = gitlabHooksUrl;
      hooksBtn.style.display = "block";
    } else {
      hooksBtn.style.display = "none";
    }
  }
  showScreen("reveal");
}

async function toggleMute() {
  try {
    const res = await fetch(
      "{{BASE_PATH}}/api/webapp/installations/" + currentItem.id + "/mute",
      {
        method: "POST",
        headers: getAuthHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify({ muted: !currentItem.muted }),
      },
    );
    if (!res.ok) {
      setAction("Could not update notifications.", false);
      return;
    }
    currentItem.muted = !currentItem.muted;
    items = items.map(function (x) {
      return x.id === currentItem.id ? currentItem : x;
    });
    renderDetail();
    setAction("Notification setting updated.", true);
  } catch (e) {
    setAction("Could not update notifications.", false);
  }
}

async function testDelivery() {
  try {
    const res = await fetch(
      "{{BASE_PATH}}/api/webapp/installations/" + currentItem.id + "/test",
      {
        method: "POST",
        headers: getAuthHeaders({ "Content-Type": "application/json" }),
      },
    );
    setAction(
      res.ok
        ? "✓ Test notification sent."
        : "Could not send test notification.",
      res.ok,
    );
  } catch (e) {
    setAction("Could not send test notification.", false);
  }
}

function setupHandlers() {
  document.querySelectorAll("[data-screen]").forEach(function (b) {
    b.addEventListener("click", function () {
      const targetScreen = b.getAttribute("data-screen");
      showScreen(targetScreen);
      if (targetScreen === "list") {
        renderList();
        loadInstallations(true);
      }
    });
  });
  document.getElementById("btnAdd").addEventListener("click", openAdd);
  document.getElementById("btnAll").addEventListener("click", function () {
    listMode = "all";
    loadInstallations();
  });
  document
    .getElementById("btnSaveIdentity")
    .addEventListener("click", saveIdentity);
  document
    .getElementById("btnCreate")
    .addEventListener("click", createInstallation);
  document.getElementById("btnRevDone").addEventListener("click", function () {
    showScreen("list");
    renderList();
    loadInstallations(true);
  });
  document.getElementById("btnReveal").addEventListener("click", function () {
    const secret = document.getElementById("revSecret");
    const hidden = secret.classList.toggle("hidden");
    document.getElementById("btnReveal").innerText = hidden ? "Reveal" : "Hide";
  });
  document.getElementById("btnCopyUrl").addEventListener("click", function () {
    const urlEl = document.getElementById("revUrl");
    const url = urlEl.innerText;
    if (navigator.clipboard) navigator.clipboard.writeText(url);
    copyValue(url, urlEl);
  });
  document.getElementById("btnCopy").addEventListener("click", function () {
    const secret = document.getElementById("revSecret");
    const val = secret.innerText;
    if (navigator.clipboard) navigator.clipboard.writeText(val);
    copyValue(val, secret);
  });
  document
    .getElementById("inDestination")
    .addEventListener("change", updateChatNameFromDestination);
  const inChatNameEl = document.getElementById("inChatName");
  if (inChatNameEl) {
    inChatNameEl.addEventListener("input", function () {
      this.removeAttribute("data-autofilled");
    });
  }
  document
    .getElementById("btnConfirmRotate")
    .addEventListener("click", confirmRotateInstallation);
  document
    .getElementById("btnConfirmDelete")
    .addEventListener("click", confirmDeleteInstallation);
}

initWebApp();
