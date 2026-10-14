package dev.regresslab.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EvaluationBenchmarks {
    @Param({"allocations", "complexity", "database-loop", "repeated-parsing", "no-regression", "noisy-control"})
    public String scenario;
    byte[][] blocks;
    int[] values;
    Set<Integer> allowed;
    Connection connection;
    ObjectMapper mapper;
    String json;
    final Allocations allocations = new Allocations();
    final Complexity complexity = new Complexity();
    final DatabaseLoop database = new DatabaseLoop();
    final RepeatedParsing parsing = new RepeatedParsing();
    final NoRegression control = new NoRegression();
    final NoisyControl noisy = new NoisyControl();

    @Setup(Level.Trial) public void setup() throws Exception {
        blocks = new byte[128][512];
        for (int i = 0; i < blocks.length; i++) Arrays.fill(blocks[i], (byte)i);
        values = new int[1024]; allowed = new HashSet<>();
        for (int i = 0; i < values.length; i++) { values[i] = i; allowed.add(i); }
        mapper = new ObjectMapper(); json = "{\"value\":7,\"payload\":\"" + "x".repeat(2048) + "\"}";
        if (scenario.equals("database-loop")) {
            // Credentials inherited only by this trusted local evaluation, never read from artifacts/model output.
            connection = DriverManager.getConnection(System.getenv("REGRESSLAB_EVAL_JDBC_URL"),
                    System.getenv().getOrDefault("REGRESSLAB_EVAL_DB_USER", "postgres"),
                    System.getenv().getOrDefault("REGRESSLAB_EVAL_DB_PASSWORD", ""));
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE eval_items (id integer PRIMARY KEY, value integer NOT NULL)");
                statement.execute("INSERT INTO eval_items SELECT i, i FROM generate_series(1, 64) i");
            }
        }
        // Actual correctness gates run in each fork before timing. Return values are consumed by JMH.
        if (!Arrays.deepEquals(allocations.copy(blocks), blocks) || complexity.matches(values, allowed) != 1024
                || parsing.total(mapper, json) != 448 || control.checksum(values) != 523776
                || noisy.checksum(values) != 523776 || connection != null && database.total(connection) != 2080)
            throw new IllegalStateException("Controlled arm changed output correctness");
    }
    @TearDown(Level.Trial) public void close() throws SQLException { if (connection != null) connection.close(); }
    @Benchmark public Object operation() throws Exception {
        return switch (scenario) {
            case "allocations" -> allocations.copy(blocks);
            case "complexity" -> complexity.matches(values, allowed);
            case "database-loop" -> database.total(connection);
            case "repeated-parsing" -> parsing.total(mapper, json);
            case "no-regression" -> control.checksum(values);
            case "noisy-control" -> noisy.checksum(values);
            default -> throw new IllegalArgumentException("Unknown controlled scenario");
        };
    }
}
