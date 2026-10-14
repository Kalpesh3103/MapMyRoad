package com.mapmyroad.backend.api.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mapmyroad.backend.domain.ingest.IngestResult;
import com.mapmyroad.backend.domain.ingest.ItemKind;
import com.mapmyroad.backend.domain.ingest.Rejection;
import com.mapmyroad.backend.domain.ingest.RejectionReason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventUploadResponseTest {

    @Test
    void assemblesCountsAndRejectionsWithWireNames() {
        UUID eventId = UUID.randomUUID();
        UUID windowId = UUID.randomUUID();
        IngestResult result = new IngestResult(12, 340, 1, 2, List.of(
                new Rejection(eventId, ItemKind.EVENT, RejectionReason.IMPLAUSIBLE_SPEED),
                new Rejection(windowId, ItemKind.WINDOW, RejectionReason.INVALID_WINDOW)));

        EventUploadResponse response = EventUploadResponse.from(result);

        assertThat(response.accepted()).isEqualTo(new EventUploadResponse.Counts(12, 340));
        assertThat(response.duplicates()).isEqualTo(new EventUploadResponse.Counts(1, 2));
        assertThat(response.rejected()).containsExactly(
                new EventUploadResponse.RejectedItem(eventId, "event", "IMPLAUSIBLE_SPEED"),
                new EventUploadResponse.RejectedItem(windowId, "window", "INVALID_WINDOW"));
    }

    @Test
    void emptyRejectionsGiveEmptyList() {
        EventUploadResponse response = EventUploadResponse.from(new IngestResult(0, 0, 0, 0, List.of()));
        assertThat(response.rejected()).isEmpty();
    }
}
