package org.example.ohara.user.api;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.user.api.dto.UserResponse;
import org.example.ohara.user.application.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal CurrentActor actor) {
        return userService.getById(actor.userId());
    }
}
