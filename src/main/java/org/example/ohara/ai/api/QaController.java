package org.example.ohara.ai.api;

import jakarta.validation.Valid;
import org.example.ohara.ai.api.dto.AskRequest;
import org.example.ohara.ai.api.dto.FeedbackRequest;
import org.example.ohara.ai.api.dto.QaEvalRequest;
import org.example.ohara.ai.api.dto.QaEvalResponse;
import org.example.ohara.ai.api.dto.QaResponse;
import org.example.ohara.ai.application.QaService;
import org.example.ohara.common.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class QaController {

    private final QaService qa;

    public QaController(QaService qa) {
        this.qa = qa;
    }

    @PostMapping("/api/enrollments/{enrollmentId}/qa")
    public QaResponse ask(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID enrollmentId,
                          @Valid @RequestBody AskRequest request) {
        return qa.ask(actor, enrollmentId, request.question(), request.lessonId());
    }

    @PostMapping("/api/qa/{questionId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID questionId,
                         @Valid @RequestBody FeedbackRequest request) {
        qa.feedback(actor, questionId, request.rating());
    }

    @PostMapping("/api/admin/ai/qa/evaluate")
    public QaEvalResponse evaluate(@AuthenticationPrincipal CurrentActor actor, @Valid @RequestBody QaEvalRequest request) {
        return qa.evaluate(actor, request);
    }
}
