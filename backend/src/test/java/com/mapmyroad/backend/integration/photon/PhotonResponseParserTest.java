package com.mapmyroad.backend.integration.photon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PhotonResponseParserTest {

    @Test
    void parsesSearchFixtureAndSkipsUnusableFeatures() {
        List<PhotonPlace> places = PhotonResponseParser.parse(fixture("search.json"));

        assertThat(places).hasSize(4);
        PhotonPlace wada = places.get(0);
        assertThat(wada.name()).isEqualTo("Shaniwar Wada");
        assertThat(wada.displayName()).isEqualTo("Shaniwar Wada, Kasba Peth, Pune, Maharashtra");
        assertThat(wada.lat()).isEqualTo(18.5195);
        assertThat(wada.lon()).isEqualTo(73.8553);
        assertThat(wada.type()).isEqualTo("attraction");
    }

    @Test
    void placeWithoutNameUsesStreetAndHouseNumberAndLocality() {
        PhotonPlace house = PhotonResponseParser.parse(fixture("search.json")).get(1);

        assertThat(house.name()).isEqualTo("Fergusson College Road 12");
        assertThat(house.displayName()).isEqualTo("Fergusson College Road 12, Shivajinagar, Pune, Maharashtra");
        assertThat(house.type()).isEqualTo("house");
    }

    @Test
    void duplicatePartsAreDroppedCaseInsensitively() {
        PhotonPlace city = PhotonResponseParser.parse(fixture("search.json")).get(2);

        assertThat(city.displayName()).isEqualTo("Pune, Maharashtra");
        assertThat(city.type()).isEqualTo("city");
    }

    @Test
    void typeFallsBackToPhotonTypeWhenOsmValueMissing() {
        PhotonPlace mumbai = PhotonResponseParser.parse(fixture("search.json")).get(3);

        assertThat(mumbai.type()).isEqualTo("city");
        assertThat(mumbai.displayName()).isEqualTo("Mumbai, Maharashtra");
    }

    @Test
    void parsesReverseFixture() {
        List<PhotonPlace> places = PhotonResponseParser.parse(fixture("reverse.json"));

        assertThat(places).hasSize(1);
        PhotonPlace place = places.get(0);
        assertThat(place.name()).isEqualTo("Laxmi Road");
        assertThat(place.displayName()).isEqualTo("Laxmi Road, Budhwar Peth, Pune, Maharashtra");
        assertThat(place.type()).isEqualTo("secondary");
        assertThat(place.lat()).isEqualTo(18.52045);
    }

    @Test
    void emptyCollectionGivesNoPlaces() {
        assertThat(PhotonResponseParser.parse(fixture("empty.json"))).isEmpty();
    }

    @Test
    void districtWinsOverLocalityAndBlankValuesAreIgnored() {
        String properties = """
                {"name": "  ", "street": "MG Road", "district": "Camp", "locality": "Ignored", "city": "Pune"}
                """;
        assertThat(PhotonResponseParser.displayName(JsonMapper.shared().readTree(properties)))
                .isEqualTo("MG Road, Camp, Pune");
    }

    @Test
    void malformedBodyIsUpstreamFailure() {
        assertThatThrownBy(() -> PhotonResponseParser.parse("<html>busy</html>"))
                .isInstanceOf(PhotonUnavailableException.class)
                .hasFieldOrPropertyWithValue("reason", PhotonUnavailableException.Reason.MALFORMED);
        assertThatThrownBy(() -> PhotonResponseParser.parse("{\"message\": \"error\"}"))
                .isInstanceOf(PhotonUnavailableException.class);
        assertThatThrownBy(() -> PhotonResponseParser.parse(null)).isInstanceOf(PhotonUnavailableException.class);
    }

    @Test
    void placeToStringHidesCoordinates() {
        PhotonPlace place = PhotonResponseParser.parse(fixture("search.json")).get(0);
        assertThat(place.toString()).doesNotContain("18.5195").doesNotContain("73.8553");
    }

    static String fixture(String name) {
        try (InputStream in = PhotonResponseParserTest.class.getResourceAsStream("/photon/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
