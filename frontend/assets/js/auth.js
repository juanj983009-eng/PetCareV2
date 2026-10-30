import { createHomeOverview } from "./home-overview.js?v=directory-icons-20261029";
import * as apiClient from "./api-client.js";
import { createAuthController } from "./auth-controller.js";
import { applyApiError, clearApiErrors } from "./form-errors.js";
import { createUsersController } from "./users-controller.js?v=users-page-6-20261029";
import { createRolesController } from "./roles-controller.js?v=roles-reference-20261026-01";
import { createOwnersController } from "./owners-controller.js?v=owners-list-all-20261029";
import { createPatientsController } from "./patients-controller.js?v=patients-metrics-7";
import { createAppointmentsController } from "./appointments-controller.js";
import { createConsultationEvaluationController } from "./consultation-evaluation-controller.js?v=images-20261026-01";

const homeOverview = createHomeOverview({ document, client: apiClient });

const usersController = createUsersController({
  document,
  client: apiClient,
  errors: { applyApiError, clearApiErrors }
});
const rolesController = createRolesController({ document, client: apiClient });
const ownersController = createOwnersController({ document, client: apiClient });
let appointmentsController = null;
const consultationEvaluationController = createConsultationEvaluationController({
  document,
  client: apiClient,
  onBackToAppointments: () => appointmentsController?.openAppointmentsView()
});
const patientsController = createPatientsController({
  document,
  client: apiClient,
  onCreateAppointment: (patient) => appointmentsController?.openCreateAppointmentDialog(patient),
  onViewConsultation: (consultationId, patientProfile) => consultationEvaluationController?.openEvaluation(consultationId, {
    readOnly: true,
    patientProfile,
    onBack: () => patientsController.returnToProfile()
  })
});
appointmentsController = createAppointmentsController({
  document,
  client: apiClient,
  onViewPatientProfile: (patientId) => patientsController.openPatientProfile(patientId),
  onAppointmentCreated: (_created, message) => patientsController.showProfileNotice(message),
  onStartConsultation: (consultation) => consultationEvaluationController.openEvaluation(consultation?.id)
});

const authController = createAuthController({
  document,
  window,
  client: apiClient,
  errors: { applyApiError, clearApiErrors },
  lifecycle: {
    authenticated: (identity) => {
      usersController.sessionReady(identity);
      homeOverview.sessionReady(identity);
      rolesController.sessionReady(identity);
      ownersController.sessionReady(identity);
      patientsController.sessionReady(identity);
      appointmentsController.sessionReady(identity);
      consultationEvaluationController.sessionReady(identity);
    },
    ended: () => {
      usersController.sessionEnded();
      homeOverview.sessionEnded();
      rolesController.sessionEnded();
      ownersController.sessionEnded();
      patientsController.sessionEnded();
      appointmentsController.sessionEnded();
      consultationEvaluationController.sessionEnded();
    }
  }
});

authController.start();
