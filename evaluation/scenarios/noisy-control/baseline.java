package dev.regresslab.evaluation;
public final class NoisyControl {
    public int checksum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }
}
