package com.circleguard.form.service;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.Question;
import com.circleguard.form.model.QuestionType;
import com.circleguard.form.model.Questionnaire;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-2: SymptomMapper — Detección de síntomas (extendido con MULTI_CHOICE y edge cases)
 *
 * Extiende los tests originales YES_NO con casos MULTI_CHOICE y respuestas nulas.
 * Criticidad: ALTA — bug aquí silencia casos positivos en formularios dinámicos.
 */
@DisplayName("UT-2: SymptomMapper — Symptom Detection Coverage")
class SymptomMapperTest {

    private final SymptomMapper mapper = new SymptomMapper();

    // ── Tests originales (conservados) ───────────────────────────────────────

    @Test
    @DisplayName("YES_NO fiebre con YES → detectado como sintomático")
    void shouldDetectSymptomsFromFever() {
        UUID questionId = UUID.randomUUID();
        Question q = Question.builder()
                .id(questionId)
                .text("Do you have a fever?")
                .type(QuestionType.YES_NO)
                .build();
        Questionnaire questionnaire = Questionnaire.builder().questions(List.of(q)).build();
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(questionId.toString(), "YES")).build();
        assertTrue(mapper.hasSymptoms(survey, questionnaire));
    }

    @Test
    @DisplayName("YES_NO fiebre con NO → no detectado")
    void shouldNotDetectSymptomsWhenNo() {
        UUID questionId = UUID.randomUUID();
        Question q = Question.builder()
                .id(questionId)
                .text("Do you have a fever?")
                .type(QuestionType.YES_NO)
                .build();
        Questionnaire questionnaire = Questionnaire.builder().questions(List.of(q)).build();
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(questionId.toString(), "NO")).build();
        assertFalse(mapper.hasSymptoms(survey, questionnaire));
    }

    // ── UT-2: Casos nuevos ────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-2a: MULTI_CHOICE con síntomas seleccionados → detectado")
    void shouldDetectSymptomsFromMultiChoiceWithSymptomKeyword() {
        UUID questionId = UUID.randomUUID();
        Question q = Question.builder()
                .id(questionId)
                .text("Select your symptoms")
                .type(QuestionType.MULTI_CHOICE)
                .build();
        Questionnaire questionnaire = Questionnaire.builder().questions(List.of(q)).build();
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(questionId.toString(), "[\"cough\", \"fatigue\"]")).build();

        assertTrue(mapper.hasSymptoms(survey, questionnaire),
                "MULTI_CHOICE no vacío con keyword 'symptoms' DEBE detectarse");
    }

    @Test
    @DisplayName("UT-2b: MULTI_CHOICE vacío → no detectado")
    void shouldNotDetectSymptomsFromEmptyMultiChoice() {
        UUID questionId = UUID.randomUUID();
        Question q = Question.builder()
                .id(questionId)
                .text("Select your symptoms")
                .type(QuestionType.MULTI_CHOICE)
                .build();
        Questionnaire questionnaire = Questionnaire.builder().questions(List.of(q)).build();
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(questionId.toString(), "[]")).build();

        assertFalse(mapper.hasSymptoms(survey, questionnaire),
                "MULTI_CHOICE vacío NO debe detectarse como sintomático");
    }

    @Test
    @DisplayName("UT-2c: responses=null → false sin NullPointerException")
    void shouldReturnFalseWhenResponsesAreNull() {
        UUID questionId = UUID.randomUUID();
        Question q = Question.builder()
                .id(questionId).text("Do you have a cough?").type(QuestionType.YES_NO).build();
        Questionnaire questionnaire = Questionnaire.builder().questions(List.of(q)).build();
        HealthSurvey survey = HealthSurvey.builder().responses(null).build();

        assertDoesNotThrow(() -> mapper.hasSymptoms(survey, questionnaire));
        assertFalse(mapper.hasSymptoms(survey, questionnaire));
    }

    @Test
    @DisplayName("UT-2d: keyword 'cough' con YES → detectado")
    void shouldDetectSymptomsFromCoughKeyword() {
        UUID questionId = UUID.randomUUID();
        Question q = Question.builder()
                .id(questionId)
                .text("Do you have a persistent cough?")
                .type(QuestionType.YES_NO)
                .build();
        Questionnaire questionnaire = Questionnaire.builder().questions(List.of(q)).build();
        HealthSurvey survey = HealthSurvey.builder()
                .responses(Map.of(questionId.toString(), "YES")).build();

        assertTrue(mapper.hasSymptoms(survey, questionnaire));
    }
}
