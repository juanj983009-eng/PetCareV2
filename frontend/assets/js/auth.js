import * as apiClient from "./api-client.js";
import { createAuthController } from "./auth-controller.js";
import { applyApiError, clearApiErrors } from "./form-errors.js";
import { createUsersController } from "./users-controller.js";
import { createRolesController } from "./roles-controller.js";

const usersController = createUsersController({
  document,
  client: apiClient,
  errors: { applyApiError, clearApiErrors }
});
const rolesController = createRolesController({ document, client: apiClient });

const authController = createAuthController({
  document,
  window,
  client: apiClient,
  errors: { applyApiError, clearApiErrors },
  lifecycle: {
    authenticated: (identity) => { usersController.sessionReady(identity); rolesController.sessionReady(identity); },
    ended: () => { usersController.sessionEnded(); rolesController.sessionEnded(); }
  }
});

authController.start();
