package de.augmentia.oc.engine.core.port.human;

import de.augmentia.oc.engine.core.model.SessionId;

import java.util.List;

/** Human-in-the-loop question source (structured questions during a run). */
public interface QuestionSource {

    /** Blocks until the user answers or the source times out. */
    Answer ask(SessionId session, List<Question> questions);

    record Question(String header, String question, List<Option> options, boolean multiple) {
        public Question {
            if (header == null) header = "";
            if (question == null) question = "";
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    record Option(String label, String description) {
        public Option {
            if (label == null) label = "";
            if (description == null) description = "";
        }
    }

    record Answer(List<String> selected, String text) {
        public Answer {
            selected = selected == null ? List.of() : List.copyOf(selected);
            if (text == null) text = "";
        }
    }
}
