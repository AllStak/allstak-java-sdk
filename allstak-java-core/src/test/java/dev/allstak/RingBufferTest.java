package dev.allstak;

import dev.allstak.buffer.RingBuffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RingBufferTest {

    @Test
    void addAndDrain_basicUsage() {
        RingBuffer<String> buffer = new RingBuffer<>(5);
        buffer.add("a");
        buffer.add("b");
        buffer.add("c");

        assertThat(buffer.size()).isEqualTo(3);

        List<String> drained = buffer.drain();
        assertThat(drained).containsExactly("a", "b", "c");
        assertThat(buffer.isEmpty()).isTrue();
    }

    @Test
    void evictsOldestWhenFull() {
        RingBuffer<Integer> buffer = new RingBuffer<>(3);
        buffer.add(1);
        buffer.add(2);
        buffer.add(3);
        buffer.add(4); // evicts 1
        buffer.add(5); // evicts 2

        List<Integer> drained = buffer.drain();
        assertThat(drained).containsExactly(3, 4, 5);
        assertThat(buffer.droppedCount()).isEqualTo(2);
    }

    @Test
    void capacityThreshold() {
        RingBuffer<String> buffer = new RingBuffer<>(10);
        for (int i = 0; i < 7; i++) buffer.add("item");
        assertThat(buffer.isAtCapacityThreshold()).isFalse();

        buffer.add("item"); // 8 = 80%
        assertThat(buffer.isAtCapacityThreshold()).isTrue();
    }

    @Test
    void drainReturnsEmptyListWhenEmpty() {
        RingBuffer<String> buffer = new RingBuffer<>(5);
        List<String> result = buffer.drain();
        assertThat(result).isEmpty();
    }

    @Test
    void multipleDrainCycles() {
        RingBuffer<String> buffer = new RingBuffer<>(3);
        buffer.add("a");
        buffer.add("b");
        assertThat(buffer.drain()).containsExactly("a", "b");

        buffer.add("c");
        buffer.add("d");
        assertThat(buffer.drain()).containsExactly("c", "d");
    }

    @Test
    void constructorRejectsZeroCapacity() {
        assertThatThrownBy(() -> new RingBuffer<>(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsNegativeCapacity() {
        assertThatThrownBy(() -> new RingBuffer<>(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrapAroundPreservesOrder() {
        RingBuffer<Integer> buffer = new RingBuffer<>(3);
        // Fill it up
        buffer.add(1); buffer.add(2); buffer.add(3);
        // Drain
        buffer.drain();
        // Add more
        buffer.add(4); buffer.add(5);
        assertThat(buffer.drain()).containsExactly(4, 5);
    }

    @Test
    void heavyOverflowPreservesLatestItems() {
        RingBuffer<Integer> buffer = new RingBuffer<>(5);
        for (int i = 0; i < 100; i++) {
            buffer.add(i);
        }
        List<Integer> drained = buffer.drain();
        assertThat(drained).containsExactly(95, 96, 97, 98, 99);
    }
}
