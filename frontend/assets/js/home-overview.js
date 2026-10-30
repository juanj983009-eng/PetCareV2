import { applyDemoPatientPortrait } from "./demo-patient-portraits.js";
import { ApiRequestError } from "./api-client.js";

// Reads the existing, tenant-authorized schedule; never infers totals from a page.
export function createHomeOverview({ document, client, now = () => new Date() }) {
  const panel = document.querySelector("#home-appointments-panel");
  if (!panel) return { sessionReady() {}, sessionEnded() {}, refresh() {}, destroy() {} };
  const get = id => document.querySelector(`#${id}`);
  let generation = 0;
  let permitted = false;
  let busy = false;
  const counts = { total: "totalElements", confirmed: "confirmedCount", waiting: "waitingCount", scheduled: "scheduledCount", noShow: "noShowCount" };
  const statuses = { PROGRAMADA: "Programada", CONFIRMADA: "Confirmada", EN_ESPERA: "En espera", EN_ATENCION: "En atención", FINALIZADA: "Finalizada", CANCELADA: "Cancelada", NO_ASISTIO: "No asistió" };
  const dateFormat = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Lima", year: "numeric", month: "2-digit", day: "2-digit" });
  const timeFormat = new Intl.DateTimeFormat("es-PE", { timeZone: "America/Lima", hour: "2-digit", minute: "2-digit", hour12: false });

  function setCounts(data = null) {
    for (const [name, field] of Object.entries(counts)) {
      document.querySelectorAll(`[data-home-count="${name}"]`).forEach(el => { el.textContent = data ? new Intl.NumberFormat("es-PE").format(data[field]) : "—"; });
    }
  }
  function clear() {
    get("home-agenda-list").replaceChildren();
    get("home-agenda-list").hidden = true;
    get("home-agenda-empty").hidden = true;
    get("home-agenda-error").hidden = true;
    setCounts();
  }
  function openAgenda() { get("appointments-nav-button")?.click(); }
  const refreshFromHome = () => { if (permitted && !busy) refresh(); };
  get("home-open-agenda")?.addEventListener("click", openAgenda);
  get("home-agenda-retry")?.addEventListener("click", refreshFromHome);
  get("dashboard-nav-button")?.addEventListener("click", refreshFromHome);

  let activityGeneration = 0;
  let activityBusy = false;
  let selectedActivityDate = null;

  function clearActivity() {
    const bars = get("home-activity-bars");
    if (bars) bars.replaceChildren();
    const tableBody = get("home-activity-table-body");
    if (tableBody) tableBody.replaceChildren();
    const selection = get("home-activity-selection");
    if (selection) selection.hidden = true;
    const empty = get("home-activity-empty");
    if (empty) empty.hidden = true;
    const error = get("home-activity-error");
    if (error) error.hidden = true;
    const body = get("home-activity-body");
    if (body) body.hidden = true;
    selectedActivityDate = null;
  }

  function selectActivityDay(item) {
    if (!item) return;
    selectedActivityDate = item.date;
    const barsContainer = get("home-activity-bars");
    if (barsContainer) {
      barsContainer.querySelectorAll(".pc-activity-bar-btn").forEach(btn => {
        const isMatch = btn.dataset.date === item.date;
        btn.classList.toggle("is-selected", isMatch);
        btn.setAttribute("aria-pressed", isMatch ? "true" : "false");
      });
    }
    const selection = get("home-activity-selection");
    const dateEl = get("home-activity-selected-date");
    const countsEl = get("home-activity-selected-counts");
    const agendaBtnLabel = get("home-activity-open-agenda-label");

    if (dateEl && countsEl && selection) {
      const parts = item.date.split("-").map(Number);
      const d = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2], 12, 0, 0));
      const formattedLong = new Intl.DateTimeFormat("es-PE", {
        timeZone: "America/Lima",
        weekday: "long",
        day: "numeric",
        month: "long"
      }).format(d);

      dateEl.textContent = formattedLong.charAt(0).toUpperCase() + formattedLong.slice(1);
      const complStr = `${item.completedCount} finalizada${item.completedCount === 1 ? "" : "s"}`;
      const otherStr = `${item.otherCount} otra${item.otherCount === 1 ? "" : "s"}`;
      countsEl.textContent = item.totalCount > 0
        ? `${complStr} · ${otherStr} (${item.totalCount} en total)`
        : "Sin citas registradas";
      if (agendaBtnLabel) {
        const monthShort = new Intl.DateTimeFormat("es-PE", { month: "short" }).format(d);
        agendaBtnLabel.textContent = `Ver agenda del ${parts[2]} de ${monthShort}`;
      }
      selection.hidden = false;
    }
  }

  function openActivityAgenda() {
    if (!selectedActivityDate) return;
    const dateInput = document.querySelector("#appointment-date-filter");
    if (dateInput) {
      dateInput.value = selectedActivityDate;
    }
    const agendaNavBtn = document.querySelector("#appointments-nav-button");
    if (agendaNavBtn) agendaNavBtn.click();
  }

  async function refreshActivity() {
    if (!permitted) return;
    const request = ++activityGeneration;
    activityBusy = true;
    clearActivity();
    const loading = get("home-activity-loading");
    if (loading) loading.hidden = false;
    const card = get("home-activity-card");
    if (card) card.setAttribute("aria-busy", "true");

    try {
      const data = await client.requestJson("/api/v1/appointments/activity");
      if (request !== activityGeneration || !permitted) return;
      if (!data || !Array.isArray(data.items) || data.items.length !== 7 ||
          data.items.some(it => !it || typeof it.date !== "string" || !Number.isSafeInteger(it.completedCount) || !Number.isSafeInteger(it.otherCount) || !Number.isSafeInteger(it.totalCount))) {
        throw new ApiRequestError({
          kind: "INVALID_RESPONSE",
          code: "INVALID_RESPONSE",
          message: "No fue posible interpretar la actividad de la clínica. Intenta nuevamente."
        });
      }

      const grandTotal = data.items.reduce((acc, it) => acc + it.totalCount, 0);
      const empty = get("home-activity-empty");
      const body = get("home-activity-body");

      if (grandTotal === 0) {
        if (empty) empty.hidden = false;
        if (body) body.hidden = true;
        return;
      }

      if (empty) empty.hidden = true;
      if (body) body.hidden = false;

      const barsContainer = get("home-activity-bars");
      const tableBody = get("home-activity-table-body");
      if (barsContainer) barsContainer.replaceChildren();
      if (tableBody) tableBody.replaceChildren();

      const maxTotal = Math.max(...data.items.map(it => it.totalCount), 1);

      data.items.forEach((item, index) => {
        if (tableBody) {
          const tr = document.createElement("tr");
          const tdDate = document.createElement("td");
          tdDate.textContent = item.date;
          const tdComp = document.createElement("td");
          tdComp.textContent = String(item.completedCount);
          const tdOth = document.createElement("td");
          tdOth.textContent = String(item.otherCount);
          const tdTot = document.createElement("td");
          tdTot.textContent = String(item.totalCount);
          tr.append(tdDate, tdComp, tdOth, tdTot);
          tableBody.append(tr);
        }

        if (barsContainer) {
          const col = document.createElement("div");
          col.className = "pc-activity-bar-col";
          const isToday = index === 6;
          if (isToday) col.classList.add("is-today");

          const btn = document.createElement("button");
          btn.type = "button";
          btn.className = "pc-activity-bar-btn";
          btn.dataset.date = item.date;
          btn.setAttribute("role", "button");
          btn.setAttribute("aria-pressed", "false");

          const parts = item.date.split("-").map(Number);
          const d = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2], 12, 0, 0));
          const dayShort = new Intl.DateTimeFormat("es-PE", {
            timeZone: "America/Lima",
            weekday: "short",
            day: "numeric"
          }).format(d);

          btn.setAttribute("aria-label", `${dayShort}: ${item.completedCount} finalizadas, ${item.otherCount} otras citas, ${item.totalCount} en total`);

          const stack = document.createElement("div");
          stack.className = "pc-activity-bar-stack";

          if (item.totalCount === 0) {
            const zeroBar = document.createElement("div");
            zeroBar.className = "pc-activity-bar-zero";
            stack.append(zeroBar);
          } else {
            const heightPercent = Math.max(Math.round((item.totalCount / maxTotal) * 100), 8);
            stack.style.height = `${heightPercent}%`;

            const otherPercent = (item.otherCount / item.totalCount) * 100;
            const completedPercent = (item.completedCount / item.totalCount) * 100;

            if (item.otherCount > 0) {
              const segOther = document.createElement("div");
              segOther.className = "pc-activity-bar-segment pc-bar-other";
              segOther.style.height = `${otherPercent}%`;
              stack.append(segOther);
            }
            if (item.completedCount > 0) {
              const segComp = document.createElement("div");
              segComp.className = "pc-activity-bar-segment pc-bar-completed";
              segComp.style.height = `${completedPercent}%`;
              stack.append(segComp);
            }
          }

          btn.append(stack);

          const label = document.createElement("span");
          label.className = "pc-activity-bar-label";
          label.textContent = isToday ? "Hoy" : dayShort;
          col.append(btn, label);

          btn.addEventListener("click", () => selectActivityDay(item));

          barsContainer.append(col);
        }
      });

      selectActivityDay(data.items[6]);

    } catch (error) {
      if (request !== activityGeneration || !permitted) return;
      const suffix = error instanceof ApiRequestError && error.correlationId ? ` Código de seguimiento: ${error.correlationId}` : "";
      const errorText = get("home-activity-error-text");
      if (errorText) {
        errorText.textContent = (error instanceof ApiRequestError ? error.message : "No fue posible consultar la actividad de la clínica.") + suffix;
      }
      const errorEl = get("home-activity-error");
      if (errorEl) errorEl.hidden = false;
    } finally {
      if (request === activityGeneration) {
        activityBusy = false;
        if (loading) loading.hidden = true;
        if (card) card.setAttribute("aria-busy", "false");
      }
    }
  }

  get("home-activity-retry")?.addEventListener("click", () => {
    if (permitted && !activityBusy) refreshActivity();
  });
  get("home-activity-open-agenda")?.addEventListener("click", openActivityAgenda);

  async function refresh() {
    if (!permitted) return;
    const request = ++generation;
    busy = true;
    clear();
    get("home-agenda-loading").hidden = false;
    panel.setAttribute("aria-busy", "true");
    const today = now();
    const parts = Object.fromEntries(dateFormat.formatToParts(today).map(p => [p.type, p.value]));
    const date = `${parts.year}-${parts.month}-${parts.day}`;
    const todayLabel = get("home-today-label");
    if (todayLabel) {
      todayLabel.textContent = new Intl.DateTimeFormat("es-PE", { timeZone: "America/Lima", weekday: "long", day: "numeric", month: "long" }).format(today);
    }
    try {
      const data = await client.requestJson(`/api/v1/appointments?date=${date}&page=0&size=20&direction=asc`);
      if (request !== generation || !permitted) return;
      if (!data || !Array.isArray(data.content) || Object.values(counts).some(k => !Number.isSafeInteger(data[k]) || data[k] < 0) || data.content.some(item => !item || typeof item.patientName !== "string" || !Number.isFinite(Date.parse(item.startsAt)))) {
        throw new ApiRequestError({ kind: "INVALID_RESPONSE", code: "INVALID_RESPONSE", message: "No fue posible interpretar la agenda. Intenta nuevamente." });
      }
      setCounts(data);
      const list = get("home-agenda-list");
      const allowedStatuses = new Set(["PROGRAMADA", "CONFIRMADA", "EN_ESPERA"]);
      const upcoming = data.content
        .filter(item => allowedStatuses.has(item.status))
        .sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt));
      const displayAppointments = upcoming.slice(0, 4);

      for (const item of displayAppointments) {
        const row = document.createElement("li");
        const avatar = document.createElement("span");
        avatar.className = "pc-home-agenda-avatar";
        avatar.setAttribute("aria-hidden", "true");
        avatar.textContent = item.patientName.trim().slice(0, 1).toUpperCase() || "P";
        applyDemoPatientPortrait(avatar, item.patientId);
        const info = document.createElement("div");
        info.className = "pc-home-agenda-info";
        const name = document.createElement("strong");
        name.textContent = item.patientName;
        const state = document.createElement("span");
        state.textContent = statuses[item.status] || "Consultar estado";
        info.append(name, state);
        const time = document.createElement("time");
        time.className = "pc-home-agenda-time";
        time.dateTime = item.startsAt;
        time.textContent = timeFormat.format(new Date(item.startsAt));
        row.append(avatar, info, time);
        list.append(row);
      }
      list.hidden = displayAppointments.length === 0;
      get("home-agenda-empty").hidden = displayAppointments.length !== 0;
    } catch (error) {
      if (request !== generation || !permitted) return;
      const suffix = error instanceof ApiRequestError && error.correlationId ? ` Código de seguimiento: ${error.correlationId}` : "";
      get("home-agenda-error-text").textContent = (error instanceof ApiRequestError ? error.message : "No fue posible consultar la agenda de hoy.") + suffix;
      get("home-agenda-error").hidden = false;
    } finally {
      if (request === generation) {
        busy = false;
        get("home-agenda-loading").hidden = true;
        panel.setAttribute("aria-busy", "false");
      }
    }
  }
  function sessionEnded() {
    ++generation;
    ++activityGeneration;
    permitted = false;
    busy = false;
    activityBusy = false;
    clear();
    clearActivity();
    get("home-agenda-loading").hidden = true;
    panel.setAttribute("aria-busy", "false");
    for (const id of ["home-appointments-panel", "home-daily-summary", "home-agenda-metrics", "home-activity-card"]) {
      const el = get(id);
      if (el) el.hidden = true;
    }
    get("home-sidebar-clinic").textContent = "PetCare Enterprise";
    clearProfile();
  }
  function sessionReady(identity) {
    sessionEnded();
    renderProfile(identity?.profile);
    const clinicName = identity?.profile?.clinicName || get("header-clinic-name")?.textContent || "PetCare Enterprise";
    get("home-sidebar-clinic").textContent = clinicName;
    permitted = identity?.authorities?.includes("APPOINTMENT_READ") === true;
    for (const id of ["home-appointments-panel", "home-daily-summary", "home-agenda-metrics", "home-activity-card"]) {
      const el = get(id);
      if (el) el.hidden = !permitted;
    }
    if (permitted) {
      refresh();
      refreshActivity();
    }
  }

  function getInitials(name) {
    if (!name || typeof name !== "string") return "U";
    const parts = name.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return "U";
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  function renderProfile(profile) {
    for (const viewId of ["users-view", "roles-view", "owners-view", "patients-view", "patient-profile-view", "consultation-evaluation-view"]) {
      const view = get(viewId);
      if (!view) continue;
      let card = view.querySelector(".pc-shared-module-profile");
      if (!card) {
        card = document.createElement("section");
        card.className = "pc-shared-profile pc-shared-module-profile";
        card.setAttribute("aria-label", "Perfil de la sesión");
        card.innerHTML = '<div class="pc-agenda-profile-header"><span class="pc-agenda-profile-avatar" data-profile="avatar" aria-hidden="true"></span><div><span class="pc-shared-profile-caption">Mi perfil</span><strong data-profile="name"></strong><span data-profile="email"></span></div></div><div class="pc-agenda-profile-row"><span>Roles</span><strong data-profile="roles"></strong></div><div class="pc-agenda-profile-row"><span>Clínica</span><strong data-profile="clinic"></strong></div><div class="pc-agenda-profile-row"><span>Sede</span><strong data-profile="branch"></strong></div>';
        const rail = ["patient-profile-view", "consultation-evaluation-view"].includes(viewId) ? null : view.querySelector(".pc-context-column");
        if (["users-view", "roles-view", "owners-view"].includes(viewId)) {
          const context = document.createElement("aside");
          context.className = "pc-directory-context";
          context.setAttribute("aria-label", "Perfil y orientación del módulo");
          const help = document.createElement("section");
          help.className = "pc-directory-help";
          const guidance = {
            "users-view": ["Gestión del equipo", "Busca al personal por nombre, correo, rol o estado.", "Edita los datos y activa o desactiva el acceso según corresponda.", "Los permisos efectivos se comprueban en cada operación."],
            "roles-view": ["Roles y permisos", "Consulta los permisos asignados a cada rol desplegando su detalle.", "Configura los permisos según las funciones del equipo.", "La asignación de roles determina las acciones disponibles para cada usuario."],
            "owners-view": ["Encuentra a un propietario", "Busca por nombre, documento o teléfono con al menos dos caracteres.", "Usa el orden de resultados para localizar el registro que necesitas.", "Comprueba los datos de contacto antes de registrar un nuevo propietario."]
          }[viewId];
          const title = document.createElement("h3");
          const icon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
          icon.setAttribute("viewBox", "0 0 24 24");
          icon.setAttribute("fill", "none");
          icon.setAttribute("stroke", "currentColor");
          icon.setAttribute("stroke-width", "1.8");
          icon.setAttribute("stroke-linecap", "round");
          icon.setAttribute("aria-hidden", "true");
          const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
          path.setAttribute("d", "M12 8h.01M12 11v6M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z");
          icon.append(path);
          title.append(icon, document.createTextNode(guidance[0]));
          help.append(title);
          for (const text of guidance.slice(1)) {
            const paragraph = document.createElement("p");
            paragraph.textContent = text;
            help.append(paragraph);
          }
          context.append(card, help);
          view.prepend(context);
        } else if (rail) rail.prepend(card); else view.prepend(card);
      }
      card.hidden = !profile;
      if (!profile) continue;
      const values = { avatar: getInitials(profile.displayName), name: profile.displayName || "Usuario",
        email: profile.email || "", roles: Array.isArray(profile.roles) && profile.roles.length ? profile.roles.join(", ") : "Sin roles asignados",
        clinic: profile.clinicName || "No disponible", branch: profile.branchName || "Sin sede asignada" };
      for (const [key, value] of Object.entries(values)) card.querySelector('[data-profile="' + key + '"]').textContent = value;
    }
    const agendaCard = get("agenda-user-profile");
    if (agendaCard) {
      agendaCard.hidden = !profile;
      if (profile) {
        const values = {
          "agenda-profile-avatar": getInitials(profile.displayName),
          "agenda-profile-name": profile.displayName || "Usuario",
          "agenda-profile-email": profile.email || "",
          "agenda-profile-roles": Array.isArray(profile.roles) && profile.roles.length ? profile.roles.join(", ") : "Sin roles asignados",
          "agenda-profile-clinic": profile.clinicName || "No disponible",
          "agenda-profile-branch": profile.branchName || "Sin sede asignada"
        };
        for (const [id, value] of Object.entries(values)) {
          const field = get(id);
          if (field) field.textContent = value;
        }
      }
    }
    const card = get("home-user-profile-card");
    if (!card) return;
    if (!profile) {
      card.hidden = true;
      return;
    }
    const avatar = get("home-profile-avatar");
    const name = get("home-profile-name");
    const email = get("home-profile-email");
    const rolesContainer = get("home-profile-roles");
    const clinic = get("home-profile-clinic");
    const branch = get("home-profile-branch");
    const details = get("home-profile-details");

    if (avatar) avatar.textContent = getInitials(profile.displayName);
    if (name) name.textContent = profile.displayName || "Usuario";
    if (email) email.textContent = profile.email || "";

    if (rolesContainer) {
      const roles = Array.isArray(profile.roles) ? profile.roles : [];
      rolesContainer.replaceChildren();
      if (!roles.length) rolesContainer.textContent = "Sin roles asignados";
      roles.forEach((role, index) => {
        if (index) rolesContainer.append(document.createTextNode(", "));
        const item = document.createElement("span");
        item.className = "pc-home-profile-role-badge";
        item.textContent = role;
        rolesContainer.append(item);
      });
    }

    if (clinic) clinic.textContent = profile.clinicName || "";
    if (branch) {
      branch.textContent = profile.branchName ? profile.branchName : "Sin sede asignada";
      if (!profile.branchName) {
        branch.classList.add("pc-home-profile-branch-unassigned");
      } else {
        branch.classList.remove("pc-home-profile-branch-unassigned");
      }
    }

    if (details) details.open = true;
    card.hidden = false;
  }

  function clearProfile() {
    document.querySelectorAll(".pc-shared-module-profile").forEach((card) => {
      card.hidden = true;
      card.querySelectorAll("[data-profile]").forEach((field) => { field.textContent = ""; });
    });
    const agendaCard = get("agenda-user-profile");
    if (agendaCard) {
      agendaCard.hidden = true;
      for (const id of ["agenda-profile-avatar", "agenda-profile-name", "agenda-profile-email", "agenda-profile-roles", "agenda-profile-clinic", "agenda-profile-branch"]) {
        const field = get(id);
        if (field) field.textContent = "";
      }
    }
    const card = get("home-user-profile-card");
    if (!card) return;
    card.hidden = true;
    const avatar = get("home-profile-avatar");
    const name = get("home-profile-name");
    const email = get("home-profile-email");
    const rolesContainer = get("home-profile-roles");
    const clinic = get("home-profile-clinic");
    const branch = get("home-profile-branch");

    if (avatar) avatar.textContent = "";
    if (name) name.textContent = "";
    if (email) email.textContent = "";
    if (rolesContainer) rolesContainer.replaceChildren();
    if (clinic) clinic.textContent = "";
    if (branch) {
      branch.textContent = "";
      branch.classList.remove("pc-home-profile-branch-unassigned");
    }
  }
  function destroy() {
    sessionEnded();
    get("home-open-agenda")?.removeEventListener("click", openAgenda);
    get("home-agenda-retry")?.removeEventListener("click", refreshFromHome);
    get("dashboard-nav-button")?.removeEventListener("click", refreshFromHome);
    get("home-activity-retry")?.removeEventListener("click", refreshActivity);
    get("home-activity-open-agenda")?.removeEventListener("click", openActivityAgenda);
  }
  return { sessionReady, sessionEnded, refresh, refreshActivity, destroy };
}
