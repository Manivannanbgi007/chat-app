package com.chatapp.status.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.chatapp.status.dto.request.CreateStatusRequest;
import com.chatapp.status.enums.StatusType;
import com.chatapp.status.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

/** Which fields belong to which status type. */
class StatusRequestRulesTest {

    private static final String KEY = "key-12345678";

    private static CreateStatusRequest text(String text, String color) {
        return new CreateStatusRequest(StatusType.TEXT, text, color, null, null, KEY);
    }

    private static FieldValidationException failure(CreateStatusRequest request) {
        try {
            StatusRequestRules.check(request);
        } catch (FieldValidationException e) {
            return e;
        }
        throw new AssertionError("expected a validation failure");
    }

    @Test
    void aTextStatusWithTextAndAColourFromTheListIsFine() {
        StatusRequestRules.BACKGROUND_COLORS.forEach(color ->
                assertThatCode(() -> StatusRequestRules.check(text("Hello", color))).doesNotThrowAnyException());
    }

    @Test
    void aTextStatusNeedsNonBlankText() {
        assertThat(failure(text(null, "#1D4ED8")).getFieldErrors()).containsKey("text");
        assertThat(failure(text("   ", "#1D4ED8")).getFieldErrors()).containsKey("text");
    }

    @Test
    void aTextStatusNeedsAColourFromTheList() {
        assertThat(failure(text("Hi", null)).getFieldErrors()).containsKey("backgroundColor");
        assertThat(failure(text("Hi", "#FFFFFF")).getFieldErrors()).containsKey("backgroundColor");
        assertThat(failure(text("Hi", "#1d4ed8")).getFieldErrors()).containsKey("backgroundColor");   // exact spelling
    }

    @Test
    void aTextStatusTakesNoMediaAndNoCaption() {
        CreateStatusRequest request = new CreateStatusRequest(StatusType.TEXT, "Hi", "#1D4ED8", UUID.randomUUID(), "c", KEY);
        assertThat(failure(request).getFieldErrors()).containsKeys("mediaId", "caption");
    }

    @Test
    void photoAndVideoStatusesNeedAMediaIdAndTakeNoTextOrColour() {
        for (StatusType type : new StatusType[] {StatusType.IMAGE, StatusType.VIDEO}) {
            assertThat(failure(new CreateStatusRequest(type, null, null, null, null, KEY)).getFieldErrors())
                    .containsKey("mediaId");
            assertThat(failure(new CreateStatusRequest(type, "t", "#1D4ED8", UUID.randomUUID(), null, KEY)).getFieldErrors())
                    .containsKeys("text", "backgroundColor");
            assertThatCode(() -> StatusRequestRules.check(
                    new CreateStatusRequest(type, null, null, UUID.randomUUID(), "caption", KEY))).doesNotThrowAnyException();
        }
    }

    @Test
    void aBlankCaptionMeansNoCaption() {
        assertThat(StatusRequestRules.normalizedCaption(null)).isNull();
        assertThat(StatusRequestRules.normalizedCaption("   ")).isNull();
        assertThat(StatusRequestRules.normalizedCaption("  Sunset ")).isEqualTo("Sunset");
    }

    @Test
    void errorsAreReportedTogether() {
        assertThatThrownBy(() -> StatusRequestRules.check(new CreateStatusRequest(StatusType.TEXT, null, null, null, null, KEY)))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFieldErrors()).containsKeys("text", "backgroundColor"));
    }
}
