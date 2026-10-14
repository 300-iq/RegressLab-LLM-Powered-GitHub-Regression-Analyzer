package dev.regresslab.evaluation;
import java.util.Set;
public final class Complexity {
    public int matches(int[] values, Set<Integer> allowed) {
        int count = 0;
        for (int value : values) {
            for (int entry : allowed) if (entry == value) { count++; break; }
        }
        return count;
    }
}
