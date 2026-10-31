package org.example.ohara.ai.application;

import org.example.ohara.user.application.AccountEvents;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A deleted person's Q&A history goes with them (docs 03 §9.4). Generated study material isn't personal. */
@Component
class QaLogAnonymization {

    private final JdbcClient jdbc;

    QaLogAnonymization(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void on(AccountEvents.Anonymizing event) {
        jdbc.sql("DELETE FROM ai_qa_log WHERE user_id = :u").param("u", event.userId()).update();
    }
}
