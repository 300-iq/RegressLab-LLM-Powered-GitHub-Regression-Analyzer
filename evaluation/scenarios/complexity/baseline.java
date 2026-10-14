package dev.regresslab.evaluation;
import java.util.Set;
public final class Complexity {
    public int matches(int[] values, Set<Integer> allowed) {
        int count = 0;
        for (int value : values) if (allowed.contains(value)) count++;
        return count;
    }
}
