package dev.allstak.backpressure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackpressureControllerTest {

    @Test
    void initialFactor_isOne_passthrough() {
        BackpressureController bp = new BackpressureController();
        assertThat(bp.currentFactor()).isEqualTo(1.0);
        assertThat(bp.scaleSampleRate(0.5)).isEqualTo(0.5);
        assertThat(bp.scaleSampleRate((Double) null)).isNull();
    }

    @Test
    void throttle_halvesFactor_recoversAdditively() {
        BackpressureController bp = new BackpressureController();
        bp.consumed(true, false);
        assertThat(bp.currentFactor()).isEqualTo(0.5);

        bp.consumed(true, false);
        assertThat(bp.currentFactor()).isEqualTo(0.25);

        // 11 clean batches @ +0.1 recovers back to 1.0.
        for (int i = 0; i < 11; i++) bp.consumed(false, false);
        assertThat(bp.currentFactor()).isEqualTo(1.0);
    }

    @Test
    void queueOverflow_alsoHalves() {
        BackpressureController bp = new BackpressureController();
        bp.consumed(false, true);
        assertThat(bp.currentFactor()).isEqualTo(0.5);
    }

    @Test
    void factorCannotGoBelowFloor() {
        BackpressureController bp = new BackpressureController();
        for (int i = 0; i < 20; i++) bp.consumed(true, false);
        assertThat(bp.currentFactor()).isGreaterThanOrEqualTo(BackpressureController.MIN_FACTOR);
    }
}
