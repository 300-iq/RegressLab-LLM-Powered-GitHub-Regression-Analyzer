package dev.regresslab.evaluation;
public final class NoRegression {
    public int checksum(int[] values) {
        return sum(values);
    }
    private int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }
}
