package com.mapmyroad.backend.api.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.api.error.FieldProblem;
import com.mapmyroad.backend.service.geocode.ReverseQuery;
import com.mapmyroad.backend.service.geocode.SearchQuery;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class GeocodeRequestParserTest {

    @Test
    void validSearchIsNormalizedWithDefaults() {
        SearchQuery query = GeocodeRequestParser.search("  Shaniwar   Wada ", null, null, null, null);

        assertThat(query.text()).isEqualTo("Shaniwar Wada");
        assertThat(query.limit()).isEqualTo(5);
        assertThat(query.lang()).isNull();
        assertThat(query.bias()).isNull();
    }

    @Test
    void biasAndLanguageAreParsed() {
        SearchQuery query = GeocodeRequestParser.search("pune", 18.52, 73.85, 10, "en-IN");

        assertThat(query.bias()).isNotNull();
        assertThat(query.bias().lat()).isEqualTo(18.52);
        assertThat(query.lang()).isEqualTo("en");
        assertThat(query.limit()).isEqualTo(10);
    }

    @Test
    void queryLengthIsCheckedAfterTrimming() {
        assertFields(() -> GeocodeRequestParser.search(null, null, null, null, null), "q");
        assertFields(() -> GeocodeRequestParser.search("   ", null, null, null, null), "q");
        assertFields(() -> GeocodeRequestParser.search("  ab   ", null, null, null, null), "q");
        assertFields(() -> GeocodeRequestParser.search("x".repeat(201), null, null, null, null), "q");
        assertFields(() -> GeocodeRequestParser.search("x".repeat(5000), null, null, null, null), "q");
        assertThat(GeocodeRequestParser.search("abc", null, null, null, null).text()).isEqualTo("abc");
        assertThat(GeocodeRequestParser.search(" " + "x".repeat(200) + " ", null, null, null, null).text())
                .hasSize(200);
    }

    @Test
    void limitMustBeOneToTen() {
        assertFields(() -> GeocodeRequestParser.search("pune", null, null, 0, null), "limit");
        assertFields(() -> GeocodeRequestParser.search("pune", null, null, 11, null), "limit");
        assertThat(GeocodeRequestParser.search("pune", null, null, 1, null).limit()).isEqualTo(1);
    }

    @Test
    void biasNeedsBothCoordinatesInRange() {
        assertFields(() -> GeocodeRequestParser.search("pune", 18.5, null, null, null), "lon");
        assertFields(() -> GeocodeRequestParser.search("pune", null, 73.8, null, null), "lat");
        assertFields(() -> GeocodeRequestParser.search("pune", 91.0, 73.8, null, null), "lat");
        assertFields(() -> GeocodeRequestParser.search("pune", 18.5, -181.0, null, null), "lon");
        assertFields(() -> GeocodeRequestParser.search("pune", Double.NaN, 73.8, null, null), "lat");
    }

    @Test
    void languageMustLookLikeALanguageCode() {
        assertFields(() -> GeocodeRequestParser.search("pune", null, null, null, "english!"), "lang");
        assertFields(() -> GeocodeRequestParser.search("pune", null, null, null, "e"), "lang");
        assertThat(GeocodeRequestParser.search("pune", null, null, null, "MR").lang()).isEqualTo("mr");
    }

    @Test
    void allProblemsAreReportedTogetherWithoutEchoingValues() {
        ApiException e = catchApi(() -> GeocodeRequestParser.search("ab", null, 73.8, 50, "??"));

        assertThat(problems(e)).extracting(FieldProblem::field).containsExactly("q", "limit", "lat", "lang");
        for (FieldProblem problem : problems(e)) {
            assertThat(problem.message()).doesNotContain("73.8").doesNotContain("ab");
        }
    }

    @Test
    void reverseNeedsBothCoordinates() {
        assertFields(() -> GeocodeRequestParser.reverse(null, null, null), "lat", "lon");
        assertFields(() -> GeocodeRequestParser.reverse(18.5, 200.0, null), "lon");
        ReverseQuery query = GeocodeRequestParser.reverse(18.5, 73.8, "en");
        assertThat(query.point().lon()).isEqualTo(73.8);
        assertThat(query.lang()).isEqualTo("en");
    }

    private static void assertFields(ThrowingCallable call, String... fields) {
        assertThat(problems(catchApi(call))).extracting(FieldProblem::field).containsExactly(fields);
    }

    private static ApiException catchApi(ThrowingCallable call) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call);
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException e = (ApiException) thrown;
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<FieldProblem> problems(ApiException e) {
        return (List<FieldProblem>) e.properties().get("errors");
    }

    @Test
    void parserNeverThrowsOtherExceptionTypes() {
        assertThatThrownBy(() -> GeocodeRequestParser.search("pune", Double.POSITIVE_INFINITY, 1.0, null, null))
                .isInstanceOf(ApiException.class);
    }
}
