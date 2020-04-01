package org.broadinstitute.hellbender.utils.pairhmm;

import org.apache.logging.log4j.LogManager;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.gcs.BucketUtils;
import org.broadinstitute.hellbender.utils.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Arrays;

public class STRDecimationTable {

    private static final int[][] DEFAULT_DECIMATION_MATRIX = new int[][] {
            {0}, // 0, 0, 0, 0, 0, 0, 0, 0 ...
            {0, 10, 10, 9, 8, 7, 5, 3, 1, 0},
            {0, 0, 9, 6, 3, 0}, // 0, 0, 0 ...
            {0, 0, 8, 4, 1, 0},
            {0, 0, 6, 0},
            {0, 0, 5, 0},
            {0, 0, 4, 0},
            {0, 0, 1},
            {0}};

    public static final String NO_DECIMATION_STR = "NONE";

    public static final String DEFAULT_DECIMATION_STR = "DEFAULT";

    public static final STRDecimationTable DEFAULT = new STRDecimationTable(DEFAULT_DECIMATION_STR);

    public static final STRDecimationTable NONE = new STRDecimationTable(NO_DECIMATION_STR);


    private final int[][] decimationMatrix;
    private final long[][] decimationMask;

    private final long[][] counts;

    public STRDecimationTable(final String spec) {
        Utils.nonNull(spec);
        final int[][] decimation;
        if (spec.equalsIgnoreCase(NO_DECIMATION_STR)) {
            decimationMatrix = new int[][] {{0}};
        } else if (spec.equalsIgnoreCase(DEFAULT_DECIMATION_STR)) {
            decimationMatrix = DEFAULT_DECIMATION_MATRIX;
        } else {
            decimationMatrix = parseDecimationMatrixFromPath(spec);
        }
        decimationMask = calculateDecimationMask(decimationMatrix);
        counts = composeDecimationCounts(decimationMask);
    }

    public long decimationMask(final int period, final int repeats) {
        if (decimationMask.length <= period) {
            return -1;
        } else if (decimationMask[period].length <= repeats) {
            return -1;
        } else {
            return decimationMask[period][repeats];
        }
    }

    private long[][] composeDecimationCounts(final long[][] decimationMask) {
        final long[][] result = new long[decimationMask.length][];
        for (int i = 0; i < result.length; i++) {
            result[i] = new long[decimationMask[i].length];
        }
        return result;
    }

    private static int[][] parseDecimationMatrixFromPath(String spec) {
        try (final BufferedReader reader = new BufferedReader(IOUtils.makeReaderMaybeGzipped(Paths.get(spec)))) {
            final String[][] values = reader.lines()
                    .filter(str -> !str.startsWith("#") && !str.trim().isEmpty())
                    .map(str -> Arrays.stream(str.split("\\s+"))
                               .mapToDouble(Double::parseDouble)
                               .toArray())
                    .toArray(String[][]::new);
            return parseDecimationMatrixValues(values, spec);
        } catch (final IOException ex) {
            throw new UserException.CouldNotReadInputFile(spec, ex);
        } catch (final NumberFormatException ex){
            throw new UserException.BadInput(String.format("input decimation file %s contains non-valid values: %s", spec, ex.getMessage()));
        }
    }

    public void printDecimationMatrix(final String path) {
        try (final PrintStream stream = IOUtils.makePrintStreamMaybeGzipped(path, BucketUtils.createFile(path))) {
            printDecimationMatrix(stream);
        } catch (final IOException ex) {
            throw new UserException.CouldNotReadInputFile(path, ex);
        } catch (final NumberFormatException ex){
            throw new UserException.BadInput(String.format("input decimation file %s contains non-valid values: %s", path, ex.getMessage()));
        }
    }

    public void printDecimationMatrix(final PrintStream stream) {
        Utils.nonNull(stream);
        for (final int[] row : decimationMatrix) {
            stream.println(Utils.join("\t", row));
        }
        stream.flush();
    }

    private static int[][] parseDecimationMatrixValues(final String[][] values, final String path) {
        Utils.nonNull(values);
        if (values.length == 0) {
            LogManager.getLogger(STRDecimationTable.class)
                    .warn("Decimation matrix path provided does not seem to contain any values, we will proceed without any decimation");
            return new int[0][];
        } else {
            int totalValues = 0;
            final int[][] result = new int[values.length][];
            for (int i = 0; i < values.length; i++) {
                final String[] row = values[i];
                final int[] rowValues = new int[values.length];
                for (int j = 0; j <  row.length; j++) {
                    final String str = row[j];
                    final int value;
                    try {
                        value = Integer.parseInt(str);
                    } catch (final NumberFormatException ex) {
                        throw badDecimationValueException(str, path, i, j, "not a valid double literal");
                    }
                    if (value < 0) {
                        throw badDecimationValueException(str, path, i, j, "negatives are not allowed");
                    } else if (Double.isNaN(value)) {
                        throw badDecimationValueException(str, path, i, j, "NaN are not allowed");
                    } else if (!Double.isFinite(value)) {
                        throw badDecimationValueException(str, path, i, j, "must be finite");
                    }
                    rowValues[j] = value;
                    totalValues++;
                }
                result[i] = rowValues;
            }
            if (totalValues == 0) {
                throw new UserException.BadInput("the input decimation matrix does contain any values:" + path);
            }
            return result;
        }
    }

    private static RuntimeException badDecimationValueException(final String str, final String path, final int i, final int j,
                                                                final String details) {
        throw new UserException.BadInput(String.format("bad decimation value found in %s for period and repeats (%d, %d) with string (%s)%s",
                path, i, j, str, details == null || details.isEmpty()? "": ": " + details));
    }

    private static long[][] calculateDecimationMask(final int[][] decimationMatrix) {
        Utils.nonNull(decimationMatrix);
        final long[][] result = new long[decimationMatrix.length][];
        for (int i = 0; i < result.length; i++) {
            final int[] row = decimationMatrix[i];
            result[i] = new long[row.length];
            for (int j = 0; j < row.length; j++) {
                result[i][j] = (1 << row[j]) - 1;
            }
        }
        return result;
    }

    public long mask(final int period, final int repeats) {
        final int p = period >= decimationMask.length ? decimationMask.length - 1 : period;
        final long[] masks = decimationMask[p];
        if (masks.length == 0) {
            return 0;
        } else if (repeats >= masks.length) {
            return masks[masks.length - 1];
        } else {
            return masks[repeats];
        }
    }

    public boolean decimate(final long mask, final int bestPeriod, final long bestPeriodRepeats) {
        if (counts.length <= bestPeriod) {
            return false;
        } else {
            final long[] periodCounts = counts[bestPeriod];
            if (bestPeriodRepeats >= periodCounts.length) {
                return false;
            } else {
                final long left = mask;
                final long right = decimationMask[bestPeriod][(int) bestPeriodRepeats];
                return ((int) left & (int) right) != 0 || ((left >> 32) & (right >> 32)) != 0;
            }
        }
    }

    public long decimationBit(int period, int repeatCount) {
        if (period >= decimationMatrix.length) {
            return 0;
        } else if (repeatCount >= decimationMatrix[period].length) {
            return 0;
        } else {
            return decimationMatrix[period][repeatCount];
        }
    }
}
