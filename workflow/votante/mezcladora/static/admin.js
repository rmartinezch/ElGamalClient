const state = {
  activeSession: null,
  server: null,
  pollId: null,
};

const els = {
  setupForm: document.getElementById("setupForm"),
  heroStatus: document.getElementById("heroStatus"),
  dynamicFrames: document.getElementById("dynamicFrames"),
  eventLog: document.getElementById("eventLog"),
  flash: document.getElementById("flash"),
  appFavicon: document.getElementById("appFavicon"),
  label: document.getElementById("label"),
  election_name: document.getElementById("election_name"),
  sid: document.getElementById("sid"),
  host: document.getElementById("host"),
  parties: document.getElementById("parties"),
  threshold: document.getElementById("threshold"),
};

function svgIconData(background, text, foreground = "white") {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="${background}"/><text x="32" y="42" text-anchor="middle" font-size="28" font-family="Arial" font-weight="700" fill="${foreground}">${text}</text></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

function updateWindowChrome(session) {
  const label = session?.label?.trim() || "Mezcladora";
  document.title = `${label} | Panel 7040`;
  if (els.appFavicon) {
    els.appFavicon.href = svgIconData("#f0b15e", "M", "#0d1824");
  }
}

function flash(message, isError = false) {
  els.flash.textContent = message;
  els.flash.style.borderColor = isError ? "rgba(217,93,82,.38)" : "rgba(240,177,94,.26)";
  els.flash.classList.add("show");
  clearTimeout(flash.timer);
  flash.timer = setTimeout(() => els.flash.classList.remove("show"), 3200);
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

function markTouched() {
  document.querySelectorAll("input").forEach((input) => {
    input.addEventListener("input", () => {
      input.dataset.touched = "1";
      if (input === els.parties) updateThresholdMax();
    });
  });
}

function updateThresholdMax() {
  const max = Number(els.parties.value || 1);
  els.threshold.max = String(max);
  if (Number(els.threshold.value) > max) {
    els.threshold.value = String(max);
  }
}

function fillDefaults(defaults) {
  Object.entries(defaults).forEach(([key, value]) => {
    const input = els[key];
    if (input && !input.dataset.touched) input.value = value;
  });
}

function artifactLabel(label, enabled) {
  return `<span class="chip ${enabled ? "on" : ""}">${label}</span>`;
}

function partyLink(host, port) {
  return `http://${host}:${port}`;
}

function renderHero(session) {
  if (!session) {
    els.heroStatus.textContent = "Sin sesión activa";
    return;
  }
  const current = session.current_operation;
  const opText = current ? `${current.name} (${current.status})` : "sin fases";
  els.heroStatus.textContent = `${session.label} | ${session.parties} parties | ${session.host} | ${opText}`;
}

function renderEvents(lines) {
  if (!lines || !lines.length) {
    els.eventLog.textContent = "Sin eventos.";
    return;
  }
  els.eventLog.textContent = lines.join("\n");
  els.eventLog.scrollTop = els.eventLog.scrollHeight;
}

function renderDynamicFrames(session, keygenReady) {
  if (!session || !keygenReady) {
    els.dynamicFrames.innerHTML = "";
    return;
  }

  const firstKey = session.key_locations?.[0];
  const lastKey = session.key_locations?.[session.key_locations.length - 1];
  els.dynamicFrames.innerHTML = `
    <section class="panel">
      <div class="panel-head">
        <h2>Sesión activa</h2>
        <p>Se generó porque la llave principal terminó correctamente.</p>
      </div>
      <div class="summary-grid">
        <div class="summary-item"><span>ID de sesión</span><strong>${session.id}</strong></div>
        <div class="summary-item"><span>Nombre visible</span><strong>${session.election_name}</strong></div>
        <div class="summary-item"><span>Nombre de protocolo</span><strong>${session.protocol_name || "N/A"}</strong></div>
        <div class="summary-item full"><span>Carpeta de trabajo</span><strong>${session.workspace}</strong></div>
        <div class="summary-item full"><span>Llave pública party01</span><strong>${firstKey ? firstKey.public_key : "N/A"}</strong></div>
        <div class="summary-item full"><span>Llave pública nativa party01</span><strong>${firstKey ? firstKey.public_key_native : "N/A"}</strong></div>
        <div class="summary-item full"><span>Última llave pública nativa</span><strong>${lastKey ? lastKey.public_key_native : "N/A"}</strong></div>
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>Ventanas y parties</h2>
        <p>Estas ventanas se generaron porque el <code>keygen</code> terminó correctamente.</p>
      </div>
      <div class="party-grid">
        ${session.party_details.map((party) => `
          <article class="party-card">
            <div class="party-title">
              <strong>${party.name}</strong>
              <span class="badge ${party.current_status === "ok" ? "good" : "bad"}">${party.current_status}</span>
            </div>
            <div class="meta">
              <span>Ventana: ${party.ui_port}</span>
              <span>Party Hint: ${session.host}:${party.hint_port}</span>
              <span>Party HTTP: ${session.host}:${party.http_port}</span>
            </div>
            <div class="window-link-row">
              <a class="window-link" href="${partyLink(state.server.ui_host, party.ui_port)}" target="_blank" rel="noreferrer">Abrir ventana ${party.ui_port}</a>
            </div>
            <div class="chips">
              ${artifactLabel("stub", party.artifacts.stub)}
              ${artifactLabel("privInfo", party.artifacts.privinfo)}
              ${artifactLabel("protInfo", party.artifacts.protinfo_global)}
              ${artifactLabel("publicKey", party.artifacts.public_key)}
              ${artifactLabel("publicKey_ext", party.artifacts.public_key_native)}
              ${artifactLabel("ciphertexts", party.artifacts.ciphertexts)}
              ${artifactLabel("ciphertextsout", party.artifacts.ciphertexts_shuffled)}
              ${artifactLabel("plaintexts", party.artifacts.plaintexts)}
            </div>
          </article>
        `).join("")}
      </div>
    </section>
  `;
}

async function refresh() {
  const res = await fetch("/api/state");
  const data = await res.json();
  state.server = data.server;
  fillDefaults(data.defaults);
  state.activeSession = data.active_session;
  updateWindowChrome(state.activeSession);
  renderHero(state.activeSession);
  renderDynamicFrames(state.activeSession, data.keygen_ready);
  renderEvents(data.general_events);
}

els.setupForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await request("/api/setup", {
      label: els.label.value.trim(),
      election_name: els.election_name.value.trim(),
      sid: els.sid.value.trim(),
      host: els.host.value.trim(),
      parties: Number(els.parties.value),
      threshold: Number(els.threshold.value),
    });
    flash("Nueva sesión creada. El monitor general capturará cualquier fallo durante protocolo o keygen.");
    await refresh();
  } catch (error) {
    flash(error.message, true);
  }
});

async function init() {
  markTouched();
  updateThresholdMax();
  els.dynamicFrames.innerHTML = "";
  updateWindowChrome(null);
  renderHero(null);
  renderEvents([]);
  await refresh();
  state.pollId = window.setInterval(refresh, 1500);
}

init().catch((error) => flash(error.message, true));
