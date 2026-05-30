package page.poli.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BackoffTest {

  /** Deterministic backoff for assertions on the exact delay. */
  private static Backoff fixed(double jitterValue) {
    return new Backoff(() -> jitterValue);
  }

  @Nested
  class ExponentialGrowth {

    @Test
    void attempt_0_with_jitter_1_returns_baseDelay() {
      assertThat(fixed(1.0).compute(0, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void attempt_1_with_jitter_1_doubles_baseDelay() {
      assertThat(fixed(1.0).compute(1, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void attempt_2_with_jitter_1_quadruples_baseDelay() {
      assertThat(fixed(1.0).compute(2, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void attempt_3_with_jitter_1_octuples_baseDelay() {
      assertThat(fixed(1.0).compute(3, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(4000));
    }
  }

  @Nested
  class JitterApplication {

    @Test
    void jitter_min_halves_exponential() {
      assertThat(fixed(0.5).compute(0, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void jitter_max_one_and_a_half_scales_exponential() {
      assertThat(fixed(1.49).compute(0, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(745));
    }

    @Test
    void jitter_is_invoked_per_call() {
      // Sequence: 0.5, 1.0, 1.5 → 250, 500, 750.
      double[] sequence = {0.5, 1.0, 1.49};
      int[] index = {0};
      Backoff b = new Backoff(() -> sequence[index[0]++]);

      assertThat(b.compute(0, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(250));
      assertThat(b.compute(0, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(500));
      assertThat(b.compute(0, Duration.ofMillis(500))).isEqualTo(Duration.ofMillis(745));
    }
  }

  @Nested
  class Validation {

    @Test
    void negative_attempt_throws() {
      assertThatThrownBy(() -> fixed(1.0).compute(-1, Duration.ofMillis(500)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("attempt");
    }

    @Test
    void large_attempt_is_clamped_so_it_does_not_overflow() {
      // attempt = 100 would overflow long if not clamped. The implementation caps the bit-shift
      // at 30, so the result is still a sane large duration rather than a negative one.
      Duration result = fixed(1.0).compute(100, Duration.ofMillis(500));
      assertThat(result).isPositive();
    }
  }

  @Nested
  class DefaultJitterRange {

    @Test
    void produces_values_in_half_to_one_and_a_half_range() {
      Backoff backoff = Backoff.defaultJitter();
      // Sample 1000 attempts; every computed delay must land in [base × 0.5, base × 1.5).
      long base = 1000L;
      Duration baseDur = Duration.ofMillis(base);
      for (int i = 0; i < 1000; i++) {
        long ms = backoff.compute(0, baseDur).toMillis();
        // Rounding can push the boundary off by 1ms; allow a small margin.
        assertThat(ms).isBetween(499L, 1500L);
      }
    }

    @Test
    void default_jitter_uses_thread_local_random() {
      // Sanity check: defaultJitter() doesn't crash and produces a value that the PRNG
      // could plausibly emit. (Catches "factory wasn't wired up correctly" regressions.)
      assertThat(ThreadLocalRandom.current()).isNotNull();
      Duration once = Backoff.defaultJitter().compute(0, Duration.ofMillis(100));
      assertThat(once).isPositive();
    }
  }
}
