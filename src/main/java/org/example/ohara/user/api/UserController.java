package org.example.ohara.user.api;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.user.api.dto.ConsentUpdateRequest;
import org.example.ohara.user.api.dto.UserResponse;
import org.example.ohara.user.application.ConsentService;
import org.example.ohara.user.application.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final ConsentService consentService;

    public UserController(UserService userService, ConsentService consentService) {
        this.userService = userService;
        this.consentService = consentService;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal CurrentActor actor) {
        return userService.getById(actor.userId());
    }

    @GetMapping("/me/consents")
    public List<ConsentService.ConsentState> consents(@AuthenticationPrincipal CurrentActor actor) {
        return consentService.current(actor.userId());
    }

    @PutMapping("/me/consents")
    public List<ConsentService.ConsentState> updateConsents(@AuthenticationPrincipal CurrentActor actor,
                                                           @RequestBody ConsentUpdateRequest request) {
        return consentService.update(actor.userId(), request.acceptTerms(), request.marketingEmails());
    }
}
