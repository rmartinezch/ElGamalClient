const form = document.getElementById("ballotForm");
const eventMonitor = document.getElementById("eventMonitor");
const receiptSummary = document.getElementById("receiptSummary");
const submitButton = document.getElementById("submitButton");

const state = {
  config: null,
  bootstrap: null,
  catalog: null,
  pollingHandle: null,
  lastReceiptAccepted: false,
  lastValidatedParty: "Pendiente"
};

const POLL_INTERVAL_MS = 5000;
const PARTY_FALLBACK_EMOJIS = ["🌳", "⭐", "🔵", "🟢", "🔴", "🟠", "🟡", "🟣", "⚪", "🟤", "🏛️", "🕊️"];

function timestamp() {
  return new Date().toLocaleTimeString("es-PE", { hour12: false });
}

function appendEvent(message) {
  const line = `[${timestamp()}] ${message}`;
  if (!eventMonitor.textContent || eventMonitor.textContent.startsWith("Inicializando")) {
    eventMonitor.textContent = line;
  } else {
    eventMonitor.textContent += `\n${line}`;
  }
  eventMonitor.scrollTop = eventMonitor.scrollHeight;
}

async function api(path, options = {}) {
  const response = await fetch(path, options);
  const text = await response.text();
  if (!response.ok) {
    throw new Error(text || response.statusText);
  }
  return text ? JSON.parse(text) : {};
}

function pad2(value) {
  return String(value).padStart(2, "0");
}

function optionLabel(kind, code) {
  if (code === "00") {
    return "00 - No registra";
  }
  if (kind === "party") {
    const party = resolvePartyCatalogEntry(code);
    return `${party.emoji} ${code} - ${party.name}`;
  }
  if (kind === "candidate") {
    const candidate = resolveCandidateCatalogEntry(code);
    return `${code} - ${candidate.name}`;
  }
  return `${code} - Opcion ${code}`;
}

function resolvePartyCatalogEntry(code) {
  const catalog = state.catalog?.partyCatalog || {};
  const party = catalog[code] || {};
  const fallbackEmoji = PARTY_FALLBACK_EMOJIS[(Number.parseInt(code, 10) - 1) % PARTY_FALLBACK_EMOJIS.length];
  return {
    emoji: party.emoji || fallbackEmoji,
    name: party.name || `Organizacion politica ${code}`
  };
}

function resolveCandidateCatalogEntry(code) {
  const catalog = state.catalog?.candidateCatalog || {};
  const candidate = catalog[code] || {};
  return {
    name: candidate.name || `Candidatura preferencial ${code}`
  };
}

function fillSelect(id, from, to, kind, includeZero = false, defaultValue = "") {
  const select = document.getElementById(id);
  select.innerHTML = "";
  if (includeZero) {
    const zero = document.createElement("option");
    zero.value = "00";
    zero.textContent = optionLabel(kind, "00");
    select.appendChild(zero);
  }
  for (let n = from; n <= to; n += 1) {
    const code = pad2(n);
    const opt = document.createElement("option");
    opt.value = code;
    opt.textContent = optionLabel(kind, code);
    select.appendChild(opt);
  }
  if (defaultValue) {
    select.value = defaultValue;
  }
}

function fillPartySelect(id, defaultValue = "") {
  const select = document.getElementById(id);
  select.innerHTML = "";
  const catalog = state.catalog?.partyCatalog || {};
  const partyCodes = Object.keys(catalog).sort();
  if (partyCodes.length === 0) {
    fillSelect(id, 1, 40, "party", false, defaultValue);
    return;
  }
  partyCodes.forEach((code) => {
    const opt = document.createElement("option");
    opt.value = code;
    opt.textContent = optionLabel("party", code);
    select.appendChild(opt);
  });
  if (defaultValue) {
    select.value = defaultValue;
  }
}

function serializeForm() {
  return new URLSearchParams(new FormData(form)).toString();
}

function text(id, value) {
  document.getElementById(id).textContent = value;
}

function setLamp(id, state) {
  const lamp = document.getElementById(id);
  if (!lamp) {
    return;
  }
  lamp.classList.remove("status-on", "status-off", "status-unknown");
  lamp.classList.add(state || "status-unknown");
}

function setBadgeState(receiptAccepted, lampState = "") {
  text("serviceSendState", receiptAccepted ? "Transmitido" : "Pendiente");
  setLamp("serviceSendLamp", lampState || (receiptAccepted ? "status-on" : "status-unknown"));
}

function setVotingAvailability(serviceMixActive, reason = "") {
  submitButton.disabled = !serviceMixActive;
  if (serviceMixActive) {
    submitButton.title = "";
    return;
  }
  submitButton.title = reason || "La mezcladora no esta activa.";
}

function tryParseJson(value) {
  try {
    return JSON.parse(value);
  } catch (error) {
    return null;
  }
}

function resolveSessionDisplay(data) {
  return (
    data.serviceSessionLabel
    || data.serviceSessionName
    || data.serviceSessionId
    || data.serviceActiveSession
    || "Sin sesion activa"
  );
}

function applyBootstrap(data) {
  state.bootstrap = data;
  state.config = data;
  const profile = data.voterProfile || {};
  text("profileFullName", profile.fullName || "-");
  text("profileDni", profile.dni || "-");
  text("profileDistrict", `${profile.electoralDistrict || "-"} (${profile.districtCode || "--"})`);
  text("profileLocal", profile.local || "-");
  text("profileMesa", profile.mesa || "-");
  text("profileOrder", profile.order || "-");
  text("profileUbigeo", profile.ubigeo || "-");
  text("profileWindow", profile.emissionWindow || "-");
  text("conditionBadge", profile.condition || "HABIL");

  document.getElementById("districtCode").value = profile.districtCode || "02";
  document.getElementById("auxsid").value = data.resolvedAuxsid || data.defaultAuxsid || "default";

  text("serviceMixState", data.serviceMixActive ? "Activa" : "Inactiva");
  setLamp("serviceMixLamp", data.serviceMixActive ? "status-on" : "status-off");
  text("serviceEndpoint", data.serviceBaseUrl || "-");
  text("serviceElectionName", data.serviceElectionName || data.electionName || "-");
  text("serviceSession", resolveSessionDisplay(data));
  text("serviceAuxsid", data.resolvedAuxsid || "Pendiente");
  text("serviceKeyState", data.publicKeyOk ? "Disponible" : "Pendiente");
  setLamp("serviceKeyLamp", data.publicKeyOk ? "status-on" : "status-off");
  text("serviceValidatedParty", state.lastValidatedParty || "Pendiente");
  setLamp("serviceValidatedLamp",
    state.lastValidatedParty && state.lastValidatedParty !== "Pendiente" ? "status-on" : "status-unknown");
  setVotingAvailability(data.serviceMixActive, data.serviceInactiveReason);
  setBadgeState(Boolean(state.lastReceiptAccepted));
}

function renderReceiptSummary(submitResult) {
  const receipt = tryParseJson(submitResult.receiptRaw);
  const writtenFiles = Array.isArray(receipt?.written_files) ? receipt.written_files.length : 0;
  const replicatedTo = Array.isArray(receipt?.replicated_to) ? receipt.replicated_to.join(", ") : "Pendiente";
  const validatedState = receipt?.validated ? "Validada" : (submitResult.receiptAccepted ? "Aceptada" : "Pendiente");
  receiptSummary.innerHTML = [
    `<div class="receipt-row"><span>Run ID</span><strong>${submitResult.runId}</strong></div>`,
    `<div class="receipt-row"><span>Eleccion</span><strong>${receipt?.election_name || state.bootstrap?.serviceElectionName || "No informada"}</strong></div>`,
    `<div class="receipt-row"><span>Sesion</span><strong>${receipt?.session_label || receipt?.session_name || receipt?.session_id || receipt?.session || resolveSessionDisplay(state.bootstrap || {})}</strong></div>`,
    `<div class="receipt-row"><span>Auxsid</span><strong>${submitResult.serviceResolvedAuxsid || submitResult.auxsid}</strong></div>`,
    `<div class="receipt-row"><span>Recepcion</span><strong>${submitResult.receiptAccepted ? "Confirmada" : "Pendiente"}</strong></div>`,
    `<div class="receipt-row"><span>Validacion</span><strong>${validatedState} en ${receipt?.party_validated || "party01"}</strong></div>`,
    `<div class="receipt-row"><span>Formato</span><strong>${receipt?.format_resolved || submitResult.ciphertextsFormat || "native"}</strong></div>`,
    `<div class="receipt-row"><span>Auxsid renombrado</span><strong>${receipt?.auxsid_changed ? "Si" : "No"}</strong></div>`,
    `<div class="receipt-row"><span>Acumulado</span><strong>${receipt?.accumulated ? "Si" : "No"}</strong></div>`,
    `<div class="receipt-row"><span>Replicado a</span><strong>${replicatedTo}</strong></div>`,
    `<div class="receipt-row"><span>Archivos escritos</span><strong>${writtenFiles}</strong></div>`,
    `<div class="receipt-row"><span>Carpeta local</span><strong>${submitResult.submissionDir}</strong></div>`
  ].join("");
}

function getSelectValue(id) {
  return document.getElementById(id).value;
}

function setSelectValue(id, value) {
  document.getElementById(id).value = value;
}

function normalizePreferentialPair(primaryId, secondaryId, label, changedId = "") {
  const primary = getSelectValue(primaryId);
  const secondary = getSelectValue(secondaryId);
  if (primary !== "00" && primary === secondary) {
    setSelectValue(secondaryId, "00");
    const source = changedId ? ` tras cambiar ${changedId}` : "";
    appendEvent(`Se corrigio duplicidad en ${label}${source}. PV2 fue restablecido a 00.`);
  }
}

function normalizePreferentialSelections(changedId = "") {
  normalizePreferentialPair("senatorsNationalPv1", "senatorsNationalPv2", "senadores nacionales", changedId);
  normalizePreferentialPair("deputiesPv1", "deputiesPv2", "diputados regionales", changedId);
  normalizePreferentialPair("andeanPv1", "andeanPv2", "Parlamento Andino", changedId);
}

async function previewBallot(logSuccess = true) {
  normalizePreferentialSelections();
  const data = await api("/api/ballot/preview", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded; charset=utf-8" },
    body: serializeForm()
  });
  if (logSuccess) {
    appendEvent("Cedula validada y lista para cifrado.");
  }
  return data;
}

function initSelects() {
  fillPartySelect("presidentialParty", "03");
  fillPartySelect("senatorsNationalParty", "04");
  fillSelect("senatorsNationalPv1", 1, 50, "candidate", true, "01");
  fillSelect("senatorsNationalPv2", 1, 50, "candidate", true, "02");
  fillPartySelect("senatorsRegionalParty", "04");
  fillSelect("senatorsRegionalPv1", 1, 50, "candidate", true, "01");
  fillPartySelect("deputiesParty", "03");
  fillSelect("deputiesPv1", 1, 50, "candidate", true, "01");
  fillSelect("deputiesPv2", 1, 50, "candidate", true, "04");
  fillPartySelect("andeanParty", "05");
  fillSelect("andeanPv1", 1, 50, "candidate", true, "03");
  fillSelect("andeanPv2", 1, 50, "candidate", true, "04");
}

document.getElementById("previewButton").addEventListener("click", async () => {
  try {
    appendEvent("Solicitando validacion de la cedula.");
    await previewBallot(true);
  } catch (error) {
    appendEvent(`Validacion rechazada: ${error.message}`);
  }
});

form.addEventListener("change", (event) => {
  normalizePreferentialSelections(event?.target?.id || "");
  previewBallot(false).catch(() => {});
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (submitButton.disabled) {
    appendEvent("Emision bloqueada: la mezcladora no reporta una sesion activa.");
    return;
  }
  try {
    appendEvent("Preparando emision del voto.");
    await previewBallot(false);
    appendEvent("Recuperando llave publica y ejecutando cifrado.");
    const data = await api("/api/ballot/submit", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded; charset=utf-8" },
      body: serializeForm()
    });
    const receipt = tryParseJson(data.receiptRaw) || {};
    renderReceiptSummary(data);
    state.lastReceiptAccepted = Boolean(data.receiptAccepted);
    state.lastValidatedParty = receipt.party_validated || "party01";
    text("serviceSession", receipt.session_label || receipt.session_name || receipt.session_id || receipt.session || resolveSessionDisplay(state.bootstrap || {}));
    text("serviceAuxsid", data.serviceResolvedAuxsid || data.auxsid);
    text("serviceKeyState", "Aplicada");
    setLamp("serviceKeyLamp", "status-on");
    text("serviceValidatedParty", state.lastValidatedParty);
    setLamp("serviceValidatedLamp", "status-on");
    setBadgeState(Boolean(data.receiptAccepted));
    if (Array.isArray(data.events)) {
      data.events.forEach((line) => appendEvent(line));
    } else {
      appendEvent("Operacion finalizada.");
    }
  } catch (error) {
    state.lastReceiptAccepted = false;
    setBadgeState(false, "status-off");
    appendEvent(`Operacion fallida: ${error.message}`);
  }
});

async function refreshOperationalState(logChanges = false) {
  const previous = state.bootstrap || {};
  const data = await api("/api/bootstrap");
  applyBootstrap(data);
  if (logChanges) {
    if (!previous.serviceMixActive && data.serviceMixActive) {
      appendEvent("Mezcladora activa. La estacion habilito la emision.");
    }
    if (previous.serviceMixActive && !data.serviceMixActive) {
      appendEvent("Mezcladora inactiva. La emision quedo bloqueada.");
    }
    const previousSession = resolveSessionDisplay(previous);
    const currentSession = resolveSessionDisplay(data);
    if (currentSession !== previousSession && currentSession !== "Sin sesion activa") {
      appendEvent(`Sesion activa detectada: ${currentSession}.`);
    }
  }
  return data;
}

async function bootstrap() {
  appendEvent("Inicializando padron local.");
  state.catalog = await api("/api/catalog");
  initSelects();
  const data = await refreshOperationalState(false);
  await previewBallot(false);
  appendEvent("Padron cargado en memoria.");
  if (data.serviceMixActive) {
    appendEvent(`Conexion establecida con ${data.serviceBaseUrl}.`);
    appendEvent(`Sesion activa detectada: ${resolveSessionDisplay(data)}.`);
    appendEvent(`Eleccion activa: ${data.serviceElectionName || data.electionName || "No informada"}.`);
    appendEvent(`Auxsid asignado automaticamente: ${data.resolvedAuxsid}.`);
    appendEvent("Llave publica disponible para la emision.");
  } else {
    appendEvent(`Mezcladora inactiva o no disponible: ${data.serviceBaseUrl}.`);
    appendEvent(`Motivo: ${data.serviceInactiveReason || "sin sesion activa."}`);
    appendEvent(`Auxsid local reservado: ${data.resolvedAuxsid}.`);
    if (data.serviceError) {
      appendEvent(`Detalle de conectividad: ${data.serviceError}`);
    }
    if (data.serviceStateError) {
      appendEvent(`Estado GUI no disponible: ${data.serviceStateError}`);
    }
  }
}

function startOperationalPolling() {
  if (state.pollingHandle) {
    clearInterval(state.pollingHandle);
  }
  state.pollingHandle = setInterval(() => {
    refreshOperationalState(true).catch((error) => {
      appendEvent(`Fallo al refrescar el estado operativo: ${error.message}`);
      setVotingAvailability(false, "No se pudo consultar la mezcladora.");
      text("serviceMixState", "Inactiva");
      setLamp("serviceMixLamp", "status-off");
    });
  }, POLL_INTERVAL_MS);
}

bootstrap().catch((error) => {
  appendEvent(`Fallo de inicializacion: ${error.message}`);
  setVotingAvailability(false, "No se pudo inicializar la estacion.");
  setLamp("serviceMixLamp", "status-off");
  setLamp("serviceKeyLamp", "status-off");
});
startOperationalPolling();
