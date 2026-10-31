package org.example.ohara.auth.api;

import jakarta.validation.Valid;
import org.example.ohara.auth.api.dto.DeletionRequest;
import org.example.ohara.auth.api.dto.DeletionStatus;
import org.example.ohara.auth.api.dto.LegalHoldRequest;
import org.example.ohara.auth.application.AccountDeletionService;
import org.example.ohara.common.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class AccountDeletionController {

    private final AccountDeletionService deletion;

    public AccountDeletionController(AccountDeletionService deletion) {
        this.deletion = deletion;
    }

    @PostMapping("/api/users/me/deletion-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeletionStatus request(@AuthenticationPrincipal CurrentActor actor, @Valid @RequestBody DeletionRequest request) {
        return deletion.request(actor, request.password());
    }

    @GetMapping("/api/admin/users/{userId}/deletion")
    public DeletionStatus get(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID userId) {
        return deletion.get(actor, userId);
    }

    @PostMapping("/api/admin/users/{userId}/deletion/cancel")
    public DeletionStatus cancel(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID userId) {
        return deletion.cancel(actor, userId);
    }

    @PutMapping("/api/admin/users/{userId}/legal-hold")
    public DeletionStatus hold(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID userId,
                               @Valid @RequestBody LegalHoldRequest request) {
        return deletion.placeLegalHold(actor, userId, request.reason());
    }

    @DeleteMapping("/api/admin/users/{userId}/legal-hold")
    public DeletionStatus release(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID userId) {
        return deletion.releaseLegalHold(actor, userId);
    }
}
