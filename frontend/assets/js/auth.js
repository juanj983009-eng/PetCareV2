import * as apiClient from "./api-client.js";
import { createAuthController } from "./auth-controller.js";
import { applyApiError, clearApiErrors } from "./form-errors.js";
import { createUsersController } from "./users-controller.js";
import { createRolesController } from "./roles-controller.js";
import { createOwnersController } from "./owners-controller.js";
import { createPatientsController } from "./patients-controller.js";
import { createAppointmentsController } from "./appointments-controller.js";

const usersController = createUsersController({
  document,
  client: apiClient,
  errors: { applyApiError, clearApiErrors }
});
const rolesController = createRolesController({ document, client: apiClient });
const ownersController = createOwnersController({ document, client: apiClient });
let appointmentsController = null;
const patientsController = createPatientsController({
  document,
  client: apiClient,
  onCreateAppointment: (patient) => appointmentsController?.openCreateAppointmentDialog(patient)
});
appointmentsController = createAppointmentsController({
  document,
  client: apiClient,
  onViewPatientProfile: (patientId) => patientsController.openPatientProfile(patientId),
  onAppointmentCreated: (_created, message) => patientsController.showProfileNotice(message)
});

const authController = createAuthController({
  document,
  window,
  client: apiClient,
  errors: { applyApiError, clearApiErrors },
  lifecycle: {
    authenticated: (identity) => {
      usersController.sessionReady(identity);
      rolesController.sessionReady(identity);
      ownersController.sessionReady(identity);
      patientsController.sessionReady(identity);
      appointmentsController.sessionReady(identity);
    },
    ended: () => {
      usersController.sessionEnded();
      rolesController.sessionEnded();
      ownersController.sessionEnded();
      patientsController.sessionEnded();
      appointmentsController.sessionEnded();
    }
  }
});

authController.start();
