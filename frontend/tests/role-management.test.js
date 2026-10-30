import { createRolesController } from "../assets/js/roles-controller.js";

const ROLE_ADMIN = "11111111-1111-4111-8111-111111111111";
const ROLE_VET = "22222222-2222-4222-8222-222222222222";
const USER = "33333333-3333-4333-8333-333333333333";
const tests = [];
window.confirm = () => true;

test("oculta la administración sin ROLE_READ", async () => {
  const controller = setup([]).controller;
  assert(document.querySelector("#roles-nav-button").hidden); controller.sessionEnded();
});

test("ROLE_READ muestra roles como texto", async () => {
  const { controller, client } = setup(["ROLE_READ"]);
  click("#roles-nav-button"); await waitFor(() => client.calls.some((call) => call.input === "/api/v1/roles"));
  assert(!document.querySelector("#roles-content").hidden);
  assert(document.querySelector("#roles-list").textContent.includes("ADMINISTRADOR"));
  assert(!document.querySelector("#roles-summary").hidden);
  assert(document.querySelector("#roles-total").textContent === "2");
  assert(document.querySelector("#roles-permissions-total").textContent === "1");
  assert(document.querySelector("#roles-list button") === null);
  controller.sessionEnded();
  assert(document.querySelector("#roles-summary").hidden);
  assert(document.querySelector("#roles-total").textContent === "");
});

test("ROLE_MANAGE actualiza permisos y asigna roles", async () => {
  const { controller, client } = setup(["ROLE_READ", "ROLE_MANAGE"]);
  click("#roles-nav-button"); await waitFor(() => document.querySelector("#roles-list button") !== null);
  document.querySelector("#roles-list button").click(); click("#save-role-permissions");
  await waitFor(() => client.calls.some((call) => call.input.includes("/permissions")));
  click("#assign-user-roles-button"); await waitFor(() => document.querySelector("#user-roles-user option") !== null);
  document.querySelector("#user-roles-list input").checked = true; click("#save-user-roles");
  await waitFor(() => client.calls.some((call) => call.input.includes(`/users/${USER}/roles`)));
  assert(client.calls.some((call) => call.input.includes(`/users/${USER}/roles`) && call.options.method === "PUT"));
  controller.sessionEnded();
});

function setup(authorities) { const client = new FakeClient(); const controller = createRolesController({ document, client }); controller.sessionReady({ authorities }); return { controller, client }; }
class FakeClient { constructor() { this.calls = []; } requestJson(input, options = {}) { this.calls.push({ input, options }); if (input === "/api/v1/roles") return Promise.resolve(catalog()); if (input.startsWith("/api/v1/users?")) return Promise.resolve({ content: [{ id: USER, firstName: "Ada", lastName: "Ficticia", email: "ada@example.test", version: 0, role: { id: ROLE_ADMIN } }] }); return Promise.resolve({}); } }
function catalog() { const permissions = [{ id: "44444444-4444-4444-8444-444444444444", code: "ROLE_READ", description: "Consultar roles", module: "IDENTITY" }]; return { availablePermissions: permissions, roles: [{ id: ROLE_ADMIN, name: "ADMINISTRADOR", description: "Rol seguro", system: true, permissions }, { id: ROLE_VET, name: "VETERINARIO", description: "Rol clínico", system: true, permissions: [] }] }; }
function test(name, body) { tests.push({ name, body }); }
function assert(value) { if (!value) throw new Error("Aserción fallida"); }
function click(selector) { document.querySelector(selector).click(); }
async function waitFor(predicate) { for (let i = 0; i < 50; i += 1) { if (predicate()) return; await new Promise((resolve) => setTimeout(resolve, 0)); } throw new Error("Tiempo agotado"); }
async function run() { let passed = 0; for (const item of tests) { try { await item.body(); passed += 1; } catch (error) { const row = document.createElement("li"); row.textContent = `FALLO — ${item.name}: ${error.message}`; document.querySelector("#test-results").append(row); } } const summary = document.querySelector("#test-summary"); summary.textContent = `${passed}/${tests.length} pruebas aprobadas`; document.documentElement.dataset.testStatus = passed === tests.length ? "passed" : "failed"; }
await run();
