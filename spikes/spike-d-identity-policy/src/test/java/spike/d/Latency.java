package spike.d;

import java.util.Arrays;

/** Percentiles over per-call nanosecond timings. Output goes to stdout so it lands in the surefire log. */
final class Latency {

    private Latency() {
    }

    static void report(String label, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        System.out.printf("LATENCY %s n=%d p50=%.1fus p90=%.1fus p99=%.1fus max=%.1fus%n",
                label, sorted.length,
                us(percentile(sorted, 50)), us(percentile(sorted, 90)),
                us(percentile(sorted, 99)), us(sorted[sorted.length - 1]));
    }

    private static long percentile(long[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static double us(long nanos) {
        return nanos / 1_000.0;
    }
}
