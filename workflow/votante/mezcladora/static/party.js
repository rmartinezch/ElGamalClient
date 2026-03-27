const state = {
  pollId: null,
  latestPayload: null,
  downloadUrl: null,
  lastSuggestedAuxsid: "default",
};

const els = {
  appFavicon: document.getElementById("appFavicon"),
  partyEyebrow: document.getElementById("partyEyebrow"),
  partyTitle: document.getElementById("partyTitle"),
  partySubtitle: document.getElementById("partySubtitle"),
  partyStatus: document.getElementById("partyStatus"),
  partySummary: document.getElementById("partySummary"),
  partyPeers: document.getElementById("partyPeers"),
  partyActionsRow: document.getElementById("partyActionsRow"),
  partyCurrentOperation: document.getElementById("partyCurrentOperation"),
  phaseTimeline: document.getElementById("phaseTimeline"),
  auxsidInput: document.getElementById("auxsidInput"),
  auxsidStatus: document.getElementById("auxsidStatus"),
  plaintextsDownloadRow: document.getElementById("plaintextsDownloadRow"),
  downloadPlaintextsBtn: document.getElementById("downloadPlaintextsBtn"),
  downloadStatus: document.getElementById("downloadStatus"),
  artifactChips: document.getElementById("artifactChips"),
  partyEvents: document.getElementById("partyEvents"),
  partyConsole: document.getElementById("partyConsole"),
};

function svgIconData(background, text, foreground = "white") {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="${background}"/><text x="32" y="42" text-anchor="middle" font-size="28" font-family="Arial" font-weight="700" fill="${foreground}">${text}</text></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

function updateWindowChrome(payload, active = true) {
  const index = payload.party_index;
  const label = `Party ${index}`;
  document.title = active && payload.active_session ? `${label} | ${payload.active_session.label}` : label;
  if (els.appFavicon) {
    els.appFavicon.href = svgIconData("#4d7ea8", String(index));
  }
}

function artifactChip(label, enabled) {
  return `<span class="chip ${enabled ? "on" : ""}">${label}</span>`;
}

function phaseBadge(kind, label) {
  return `<span class="phase-badge ${kind}">${label}</span>`;
}

function currentAuxsidValue() {
  return (els.auxsidInput.value || "default").trim() || "default";
}

function findOperation(operations, action) {
  return [...(operations || [])].reverse().find((item) => item.name === action) || null;
}

function stateForAction(payload, action) {
  const operations = payload.operations || [];
  const controls = payload.controls || { controls: {} };
  const partyName = payload.party?.name;
  const op = findOperation(operations, action);
  const ownState = op?.party_states?.find((item) => item.party === partyName)?.status;
  const meta = controls.controls?.[action] || { enabled: false, reason: "No disponible." };

  if (ownState === "running") {
    return { kind: "running", label: "en ejecución", reason: "Esta party ya inició la fase." };
  }
  if (ownState === "ok") {
    if (op?.status === "ok") {
      return { kind: "done", label: "completada", reason: "La fase terminó correctamente para todas las parties." };
    }
    return { kind: "started", label: "ya iniciada", reason: "Esta party ya arrancó la fase y espera a las siguientes." };
  }
  if (meta.enabled) {
    return { kind: "turn", label: "mi turno", reason: meta.reason };
  }
  if (meta.reason.startsWith("Esperando que party")) {
    return { kind: "wait", label: "esperando party anterior", reason: meta.reason };
  }
  if (controls.active_operation === action && controls.turn_party && controls.turn_party !== partyName) {
    return { kind: "wait", label: "esperando party anterior", reason: meta.reason };
  }
  if (action === "precomp" && meta.reason.includes("omitida")) {
    return { kind: "optional", label: "omitida", reason: meta.reason };
  }
  if (op?.status === "ok") {
    return { kind: "done", label: "completada", reason: "La fase ya terminó para todas las parties." };
  }
  return { kind: "blocked", label: "bloqueada", reason: meta.reason };
}

function renderPhaseTimeline(payload) {
  if (!payload.active_session || !payload.party || !payload.party.artifacts.public_key_native) {
    els.phaseTimeline.className = "phase-grid empty";
    els.phaseTimeline.textContent = "Sin fases activas.";
    return;
  }

  const actions = [
    ["precomp", "Precomputación"],
    ["shuffle", "Mezclado"],
    ["decrypt", "Descifrado"],
    ["verify", "Verificación"],
  ];

  els.phaseTimeline.className = "phase-grid";
  els.phaseTimeline.innerHTML = actions.map(([action, label]) => {
    const phase = stateForAction(payload, action);
    return `
      <article class="phase-card ${phase.kind}" data-phase="${action}">
        <div class="phase-head">
          <strong>${label}</strong>
          ${phaseBadge(phase.kind, phase.label)}
        </div>
        <p>${phase.reason}</p>
      </article>
    `;
  }).join("");
}

function decryptedAuxsids(payload) {
  return [...(payload.operations || [])]
    .filter((item) => item.name === "decrypt" && item.status === "ok")
    .map((item) => item.payload?.auxsid || "default");
}

function updateDownloadControls(payload) {
  const session = payload.active_session;
  const party = payload.party;
  const selectedAuxsid = currentAuxsidValue();
  const available = new Set(decryptedAuxsids(payload));

  if (!session || !party || !party.artifacts.public_key_native) {
    els.plaintextsDownloadRow.hidden = true;
    els.downloadPlaintextsBtn.disabled = true;
    state.downloadUrl = null;
    els.downloadStatus.textContent = "Los plaintexts aparecerán para descarga cuando termine el descifrado del AuxSID seleccionado.";
    return;
  }

  const href = `/api/plaintexts/download?auxsid=${encodeURIComponent(selectedAuxsid)}&party=${party.id}`;
  if (available.has(selectedAuxsid)) {
    els.plaintextsDownloadRow.hidden = false;
    els.downloadPlaintextsBtn.disabled = false;
    state.downloadUrl = href;
    els.downloadStatus.textContent = `Descarga disponible para AuxSID ${selectedAuxsid} desde ${party.name}.`;
  } else {
    els.plaintextsDownloadRow.hidden = true;
    els.downloadPlaintextsBtn.disabled = true;
    state.downloadUrl = null;
    els.downloadStatus.textContent = `Todavía no hay plaintexts disponibles para AuxSID ${selectedAuxsid} en ${party.name}.`;
  }
}

async function request(path, payload) {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const data = await res.json();
  if (!res.ok || data.error) {
    throw new Error(data.error || "La operación falló.");
  }
  return data;
}

function renderInactive(payload) {
  updateWindowChrome(payload, false);
  els.partyEyebrow.textContent = `Ventana ${payload.ui_port}`;
  els.partyTitle.textContent = `Party ${payload.party_index} sin sesión`;
  els.partySubtitle.textContent = "El puerto 7040 todavía no ha creado una sesión válida para esta party.";
  els.partyStatus.textContent = "Sin sesión activa";
  els.partySummary.className = "field chips empty";
  els.partySummary.textContent = "Esperando configuración inicial.";
  els.partyPeers.className = "field chips empty";
  els.partyPeers.textContent = "Sin participantes.";
  els.partyActionsRow.hidden = true;
  els.partyActionsRow.style.display = "none";
  els.plaintextsDownloadRow.hidden = true;
  els.partyCurrentOperation.textContent = "Sin operación lanzada.";
  els.auxsidStatus.textContent = "AuxSID sugerido: default";
  els.phaseTimeline.className = "phase-grid empty";
  els.phaseTimeline.textContent = "Sin fases activas.";
  els.artifactChips.className = "field chips empty";
  els.artifactChips.textContent = "Sin artefactos.";
  els.partyEvents.textContent = "Sin eventos.";
  els.partyConsole.textContent = "Sin salida todavía.";
}

function renderActive(payload) {
  const session = payload.active_session;
  const party = payload.party;
  const op = payload.latest_operation;
  const controls = payload.controls || { controls: {} };
  const isReady = party.artifacts.public_key_native;
  updateWindowChrome(payload, true);

  els.partyEyebrow.textContent = `Ventana ${payload.ui_port}`;
  els.partyTitle.textContent = `Party ${payload.party_index} - ${session.label}`;
  els.partySubtitle.textContent = `${session.election_name} | SID ${session.sid} | carpeta ${session.workspace}`;
  els.partyStatus.textContent = op ? `${op.name} (${op.status})` : "sin operaciones";

  els.partySummary.className = "field chips";
  els.partySummary.innerHTML = `
    <span class="chip on">Party = ${party.name}</span>
    <span class="chip on">HTTP = ${party.http_port}</span>
    <span class="chip on">Hint = ${party.hint_port}</span>
    <span class="chip on">Estado = ${party.current_status}</span>
    <span class="chip on">Última fase = ${payload.latest_log_operation || "sin logs"}</span>
  `;

  els.partyPeers.className = "field chips";
  els.partyPeers.innerHTML = payload.all_parties.map((item) => `
    <span class="chip ${item.id === party.id ? "on current-chip" : item.current_status === "ok" ? "on" : ""}">
      ${item.name} / ${item.current_status}
    </span>
  `).join("");

  els.partyActionsRow.hidden = !isReady;
  els.partyActionsRow.style.display = isReady ? "grid" : "none";
  const auxsids = payload.auxsids || {};
  const suggestedAuxsid = auxsids.suggested || "default";
  const usedAuxsids = auxsids.used?.length ? auxsids.used.join(", ") : "ninguno";
  const pendingAuxsids = auxsids.pending_ciphertexts?.length ? auxsids.pending_ciphertexts.join(", ") : "ninguno";
  state.lastSuggestedAuxsid = suggestedAuxsid;
  els.auxsidStatus.textContent = `AuxSID sugerido: ${suggestedAuxsid} | usados: ${usedAuxsids} | pendientes: ${pendingAuxsids}`;

  const pendingHints = [];
  if (!party.artifacts.ciphertexts) {
    pendingHints.push("faltan ciphertexts");
  }
  if (!party.artifacts.ciphertexts_shuffled) {
    pendingHints.push("no hay mezcla");
  }
  const turnParty = controls.turn_party ? ` | Turno: ${controls.turn_party}` : "";
  const suffix = pendingHints.length ? ` | ${pendingHints.join(" | ")}` : "";
  els.partyCurrentOperation.textContent = op
    ? `Operación actual: ${op.name} (${op.status})${turnParty}${suffix}`
    : `Sin operación lanzada.${turnParty}${suffix}`;

  document.querySelectorAll("[data-action]").forEach((button) => {
    const action = button.dataset.action;
    if (action === "ciphertexts") {
      const enabled = party.id === 1 && isReady && !(op && op.status === "running");
      button.disabled = !enabled;
      button.hidden = party.id !== 1;
      button.title = enabled ? "Generar ciphertexts de prueba y replicarlos a las demás parties." : "Disponible solo en party01.";
      return;
    }
    const meta = controls.controls?.[action] || { enabled: false, reason: "No disponible." };
    button.disabled = !meta.enabled;
    button.hidden = false;
    button.title = meta.reason;
  });

  renderPhaseTimeline(payload);
  updateDownloadControls(payload);

  els.artifactChips.className = "field chips";
  els.artifactChips.innerHTML = [
    artifactChip("stub", party.artifacts.stub),
    artifactChip("privInfo", party.artifacts.privinfo),
    artifactChip("protInfo", party.artifacts.protinfo_global),
    artifactChip("publicKey", party.artifacts.public_key),
    artifactChip("publicKey_ext", party.artifacts.public_key_native),
    artifactChip("ciphertexts", party.artifacts.ciphertexts),
    artifactChip("ciphertextsout", party.artifacts.ciphertexts_shuffled),
    artifactChip("plaintexts", party.artifacts.plaintexts),
  ].join("");

  els.partyEvents.textContent = session.events.length ? session.events.join("\n") : "Sin eventos.";
  els.partyEvents.scrollTop = els.partyEvents.scrollHeight;

  els.partyConsole.textContent = payload.console_lines.length ? payload.console_lines.join("\n") : "Sin salida todavía.";
  els.partyConsole.scrollTop = els.partyConsole.scrollHeight;
}

async function refresh() {
  const res = await fetch("/api/party-state");
  const payload = await res.json();
  state.latestPayload = payload;
  if (!payload.active_session || !payload.party) {
    renderInactive(payload);
    return;
  }
  renderActive(payload);
}

async function init() {
  els.auxsidInput.addEventListener("input", () => {
    if (state.latestPayload) {
      updateDownloadControls(state.latestPayload);
    }
  });
  els.downloadPlaintextsBtn.addEventListener("click", () => {
    if (!state.downloadUrl) {
      return;
    }
    window.location.assign(state.downloadUrl);
  });
  document.querySelectorAll("[data-action]").forEach((button) => {
    button.addEventListener("click", async () => {
      try {
        const result = await request("/api/operation", {
          action: button.dataset.action,
          auxsid: els.auxsidInput.value.trim() || "default",
        });
        if (result.resolved_auxsid) {
          els.auxsidInput.value = result.resolved_auxsid;
          state.lastSuggestedAuxsid = result.resolved_auxsid;
        }
        await refresh();
      } catch (error) {
        els.partyCurrentOperation.textContent = `Error: ${error.message}`;
        els.partyConsole.textContent = `${els.partyConsole.textContent}\n[GUI] ${error.message}`.trim();
      }
    });
  });
  await refresh();
  state.pollId = window.setInterval(refresh, 1200);
}

init();
