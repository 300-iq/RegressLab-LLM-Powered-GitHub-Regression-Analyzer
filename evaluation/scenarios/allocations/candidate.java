package dev.regresslab.evaluation;
import java.util.Arrays;
public final class Allocations {
    public byte[][] copy(byte[][] values) {
        byte[][] copies = new byte[values.length][];
        for (int i = 0; i < values.length; i++) copies[i] = Arrays.copyOf(values[i], values[i].length);
        return copies;
    }
}
