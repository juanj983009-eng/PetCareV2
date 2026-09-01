import { createPatientsController } from "../assets/js/patients-controller.js";
const results = document.querySelector("#test-results");
const client = { requestJson: async () => ({}) };
const controller = createPatientsController({ document, client });
controller.sessionReady({ authorities: ["PATIENT_CREATE"] });
const fields = [["patient-owner-id", "patient-owner-id-feedback"], ["patient-name", "patient-name-feedback"], ["patient-species", "patient-species-feedback"], ["patient-sex", "patient-sex-feedback"]];
let passed = 0;
for (const [field, feedback] of fields) {
  const control = document.querySelector(`#${field}`);
  if (control.getAttribute("aria-describedby") === feedback && document.querySelector(`#${feedback}`)) passed += 1;
  else { const row = document.createElement("li"); row.textContent = `FALLO — ${field}`; results.append(row); }
}
document.querySelector("#test-summary").textContent = `${passed}/${fields.length} asociaciones ARIA aprobadas`;
document.documentElement.dataset.testStatus = passed === fields.length ? "passed" : "failed";
