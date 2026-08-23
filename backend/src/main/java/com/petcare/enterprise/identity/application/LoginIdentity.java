package com.petcare.enterprise.identity.application;

import com.petcare.enterprise.identity.domain.UserAccount;
import java.util.UUID;

public record LoginIdentity(UserAccount user, UUID branchId) {
}
