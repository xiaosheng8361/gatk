package org.broadinstitute.hellbender.tools.copynumber;

import org.broadinstitute.barclay.argparser.*;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.CopyNumberProgramGroup;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.io.Resource;
import org.broadinstitute.hellbender.utils.python.PythonScriptExecutor;

import java.io.File;
import java.util.*;

/**
 * Caller that determines which segments of the genome have copy number events. These should be the modelFinal.seg
 * TSV files generated by {@link ModelSegments}.
 *
 * <h3>Introduction</h3>
 *
 * <p>Performing copy number variation calls is a common task in genomics and cancer research. Hereby, we implement
 * a caller that determines the copy number events based on both copy number and allele fraction data. </p>
 *
 * <p>The input data are provided by {@link ModelSegments}, and they characterize the posterior copy number
 * and allele fraction distribution of each segment. {@link CallModeledSegments} recovers the distributions from this
 * data and samples data points from each segment. The number of points is chosen proportional to the length
 * of the segments. <p/>
 *
 * <p>The sampled data is then clustered using scikit-learn by fitting Gaussians to it in
 * (copy_ratio, allele_fraction) space. The Gaussian corresponding to normal segments is identified based
 * on the following criteria:</p>
 * <pre>The allele fraction value of the mean of the normal peak has to be within one standard deviation
 * from the (0.475, 0.500) interval. </pre>
 * <pre>The copy ratio of the mean of the normal peak needs to be in the range of 'normal copy ratio values',
 * as identified below</pre>
 *
 * <p>The range of 'normal copy ratio values' is identified as the range of copy ratio data arising from
 * copy number 2 events. First, {@link CallModeledSegments} looks for clusters in the one dimensional copy ratio data by
 * fitting Gaussians to it. The peak with the lowest non-zero copy ratio either comes from copy number 1 or
 * copy number 2 events. If this peak has considerable fraction of points close to 0.5, then we say that it
 * comes from copy number 2 events. Otherwise, we consider the peak of second lowest copy ratio to be the copy
 * number 2 peak.
 * </p>
 *
 * <h3>Usage examples</h3>
 *
 * <pre>
 * gatk CallModeledSegments \
 *   --input somatic_modelFinal.seg \
 *   --load_copy_ratio true \
 *   --load_allele_fraction true \
 *   --output_image_dir output_fig_dir \
 *   --output_calls_dir output_file_dir \
 *   --output_image_prefix somatic_1 \
 *   --output_calls_prefix somatic_1
 * </pre>
 * @author Marton Kanasz-Nagy &lt;mkanaszn@broadinstitute.org&gt;
 */
@CommandLineProgramProperties(
        summary = "Determines the baseline contig ploidy for germline samples given counts data",
        oneLineSummary = "Determines the baseline contig ploidy for germline samples given counts data",
        programGroup = CopyNumberProgramGroup.class
)
@DocumentedFeature
@BetaFeature
public final class CallModeledSegments extends CommandLineProgram {
    public enum RunMode {
        COHORT, CASE
    }

    // Arugments given by the user
    private static final String SEGMENT_CALLER_PYTHON_SCRIPT = "modeled_segments_caller_cli.py";
    public static final String LOAD_COPY_RATIO_LONG_NAME = "load_copy_ratio";
    public static final String LOAD_ALLELE_FRACTION_LONG_NAME = "load_allele_fraction";
    public static final String OUTPUT_IMAGE_DIR_LONG_NAME = "output_image_dir";
    public static final String OUTPUT_CALLS_DIR_LONG_NAME = "output_calls_dir";
    public static final String OUTPUT_LOG_DIR_LONG_NAME = "output_log_dir";
    public static final String OUTPUT_IMAGE_PREFIX_LONG_NAME = "output_image_prefix";
    public static final String OUTPUT_CALLS_PREFIX_LONG_NAME = "output_calls_prefix";
    public static final String OUTPUT_LOG_PREFIX_LONG_NAME = "output_log_prefix";
    public static final String OUTPUT_IMAGE_SUFFIX_LONG_NAME = "output_image_suffix";
    public static final String OUTPUT_CALLS_SUFFIX_LONG_NAME = "output_calls_suffix";
    public static final String OUTPUT_IMAGE_SUFFIX_DEFAULT_VALUE = ".jpg";
    public static final String OUTPUT_CALLS_SUFFIX_DEFAULT_VALUE = ".called.seg";
    public static final String INTERACTIVE_RUN_LONG_NAME = "interactive";

    public static final String NORMAL_MINOR_ALLELE_FRACTION_THRESHOLD = "normal_minor_allele_fraction_threshold";
    public static final String COPY_RATIO_PEAK_MIN_WEIGHT = "copy_ratio_peak_min_weight";
    public static final String MIN_FRACTION_OF_POINTS_IN_NORMAL_ALLELE_FRACTION_REGION = "min_fraction_of_points_in_normal_allele_fraction_region";

    // Adiditional arguments
    private static final String INTERACTIVE_OUTPUT_DEL_AMPL_IMAGE_SUFFIX = "interactive_output_del_ampl_image_suffix";
    public static final String INTERACTIVE_OUTPUT_DEL_AMPL_IMAGE_SUFFIX_DEFAULT_VALUE = "_del_ampl.jpg";
    private static final String INTERACTIVE_OUTPUT_SCATTER_PLOT_SUFFIX = "interactive_output_scatter_plot_suffix";
    public static final String INTERACTIVE_OUTPUT_SCATTER_PLOT_SUFFIX_DEFAULT_VALUE = "_scatter_plot.jpg";
    private static final String INTERACTIVE_OUTPUT_ALLELE_FRACTION_PLOT_SUFFIX = "interactive_output_allele_fraction_plot_suffix";
    public static final String INTERACTIVE_OUTPUT_ALLELE_FRACTION_PLOT_SUFFIX_DEFAULT_VALUE = "_allele_fraction_CN1_and_CN2_candidate_intervals.jpg";
    private static final String INTERACTIVE_OUTPUT_COPY_RATIO_SUFFIX = "interactive_output_copy_ratio_suffix";
    public static final String INTERACTIVE_OUTPUT_COPY_RATIO_SUFFIX_DEFAULT_VALUE = "_copy_ratio_fit.jpg";
    private static final String INTERACTIVE_OUTPUT_COPY_RATIO_CLUSTERING_SUFFIX = "interactive_output_copy_ratio_clustering_suffix";
    public static final String INTERACTIVE_OUTPUT_COPY_RATIO_CLUSTERING_SUFFIX_DEFAULT_VALUE = "_copy_ratio_clusters.jpg";

    @Argument(
            doc = "Input .seg file, as generated by ModelSegments. " +
                    "It should contain data characterizing the copy ratio and allele fraction posterior distributions.",
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME,
            minElements = 1
    )
    private List<File> inputFile = new ArrayList<>();

    @Argument(
            doc = "Prefix of output image files.",
            fullName = OUTPUT_IMAGE_PREFIX_LONG_NAME,
            optional = false
    )
    private String outputImagePrefix="";

    @Argument(
            doc = "Prefix of output calls file.",
            fullName = OUTPUT_CALLS_PREFIX_LONG_NAME,
            optional = false
    )
    private String outputCallsPrefix="";

    @Argument(
            doc = "Prefix of output image files.",
            fullName = OUTPUT_IMAGE_SUFFIX_LONG_NAME,
            optional = true
    )
    private String outputImageSuffix=OUTPUT_IMAGE_SUFFIX_DEFAULT_VALUE;

    @Argument(
            doc = "Prefix of output calls file.",
            fullName = OUTPUT_CALLS_SUFFIX_LONG_NAME,
            optional = true
    )
    private String outputCallsSuffix=OUTPUT_CALLS_SUFFIX_DEFAULT_VALUE;

    @Argument(
            doc = "Output directory for plots. ",
            fullName = OUTPUT_IMAGE_DIR_LONG_NAME,
            optional = false
    )
    private String outputImageDir="";

    @Argument(
            doc = "Output directory for the results file.",
            fullName = OUTPUT_CALLS_DIR_LONG_NAME,
            optional = false
    )
    private String outputCallsFileDir="";

    @Argument(
            doc = "Output directory for the log file.",
            fullName = OUTPUT_LOG_DIR_LONG_NAME,
            optional = true
    )
    private String outputLogDir="";

    @Argument(
            doc = "Whether auxiliary plots should be saved.",
            fullName = INTERACTIVE_RUN_LONG_NAME,
            optional = true
    )
    private String interactiveRun="true";

    @Argument(
            doc = "Whether copy ratio data should be loaded. ",
            fullName = LOAD_COPY_RATIO_LONG_NAME,
            optional = true
    )
    private String loadCopyRatio="true";

    @Argument(
            doc = "Whether allele fraction data should be loaded.",
            fullName = LOAD_ALLELE_FRACTION_LONG_NAME,
            optional = true
    )
    private String loadAlleleFraction="true";

    @Argument(
            doc = "Prefix of the log file.",
            fullName = OUTPUT_LOG_PREFIX_LONG_NAME,
            optional = true
    )
    private String logFilenamePrefix="";

    @Argument(
            doc = "If the allele fraction value of a peak fitted to the data is above this threshold and its copy ratio "
                    + "value is within the appropriate region, then the peak is considered normal.",
            fullName = NORMAL_MINOR_ALLELE_FRACTION_THRESHOLD,
            optional = true
    )
    private double normalMinorAlleleFractionThreshold=0.475;

    @Argument(
            doc = "During the copy ratio clustering, peaks with weights smaller than this ratio are not taken into " +
                    "account.",
            fullName = COPY_RATIO_PEAK_MIN_WEIGHT,
            optional = true
    )
    private double copyRatioPeakMinWeight=0.03;

    @Argument(
            doc = "The region of copy ratio values are is considered normal only if at least this fraction of points " +
                    "are above the normalMinorAlleleFractionThreshold",
            fullName = MIN_FRACTION_OF_POINTS_IN_NORMAL_ALLELE_FRACTION_REGION,
            optional = true
    )
    private double minFractionOfPointsInNormalAlleleFractionRegion=0.15;

    @Override
    protected void onStartup() {
        PythonScriptExecutor.checkPythonEnvironmentForPackage("modeled_segments_caller");
    }

    @Override
    protected Object doWork() {
        Utils.validateArg(normalMinorAlleleFractionThreshold < 0.5 && 0.0 <= normalMinorAlleleFractionThreshold,
                "Minor allele fraction threshold for normal peaks has to be between 0 and 0.5.");
        Utils.validateArg(copyRatioPeakMinWeight < 1.0 && 0.0 <= copyRatioPeakMinWeight,
                "Weight threshold for copy ratio peaks considered to be normal needs to be between 0 and 1.");
        Utils.validateArg(minFractionOfPointsInNormalAlleleFractionRegion < 1.0
                                   && 0.0 <= minFractionOfPointsInNormalAlleleFractionRegion,
                "The fraction of points in the range of allele fractions is always between 0 and 1.");

        final File inFile = inputFile.get(0);
        logger.info(String.format("Retrieving copy ratio and allele fraction data from %s...", inFile));
        final boolean loadCR = Boolean.parseBoolean(loadCopyRatio);
        final boolean loadAF = Boolean.parseBoolean(loadAlleleFraction);
        final boolean interactive = Boolean.parseBoolean(interactiveRun);
        if (interactive && outputImagePrefix.equals("")) {
                // In case no name prefix is given to the images, we specify it using the input file's path
                String strArray0[] = inFile.getAbsolutePath().split("/");
                String strArray1[] = strArray0[strArray0.length - 1].split(".");
                outputImagePrefix = strArray1[0];
        }

        if (interactive && outputCallsPrefix.equals("")) {
            // In case no name prefix is given to the calls file, we specify it using the input file's path
            String strArray0[] = inFile.getAbsolutePath().split("/");
            String strArray1[] = strArray0[strArray0.length - 1].split(".");
            outputCallsPrefix = strArray1[0];
        }

        //call python inference code
        final boolean pythonReturnCode = executeSegmentCaller(
                inFile, loadCR, loadAF, interactive);

        if (!pythonReturnCode) {
            throw new UserException("Python return code was non-zero.");
        }

        logger.info("Germline contig ploidy determination complete.");

        return "SUCCESS";
    }

    private boolean executeSegmentCaller(final File inFile, boolean loadCR, boolean loadAF, boolean interactive) {
        final PythonScriptExecutor executor = new PythonScriptExecutor(true);
        final String outputImageDirArg = Utils.nonEmpty(outputImageDir).endsWith(File.separator)
                ? outputImageDir
                : outputImageDir + File.separator;

        final String outputFileDirArg = Utils.nonEmpty(outputCallsFileDir).endsWith(File.separator)
                ? outputCallsFileDir
                : outputCallsFileDir + File.separator;

        final String outputLogDirArg = Utils.nonEmpty(outputLogDir).endsWith(File.separator)
                ? outputLogDir
                : outputLogDir + File.separator;

        final String script;
        script = SEGMENT_CALLER_PYTHON_SCRIPT;

        final List<String> arguments = new ArrayList<>(Arrays.asList(
                "--input_file=" + inFile.getAbsolutePath(),
                "--" + OUTPUT_CALLS_DIR_LONG_NAME + "=" + outputFileDirArg,
                "--" + OUTPUT_IMAGE_DIR_LONG_NAME + "=" + outputImageDirArg,
                "--" + OUTPUT_LOG_DIR_LONG_NAME + "=" + outputLogDirArg,
                "--" + OUTPUT_IMAGE_PREFIX_LONG_NAME + "=" + String.valueOf(outputImagePrefix),
                "--" + OUTPUT_CALLS_PREFIX_LONG_NAME + "=" + String.valueOf(outputCallsPrefix),
                "--" + OUTPUT_IMAGE_SUFFIX_LONG_NAME + "=" + String.valueOf(outputImageSuffix),
                "--" + OUTPUT_CALLS_SUFFIX_LONG_NAME + "=" + String.valueOf(outputCallsSuffix),
                "--" + LOAD_COPY_RATIO_LONG_NAME + "=" + String.valueOf(loadCR),
                "--" + LOAD_ALLELE_FRACTION_LONG_NAME + "=" + String.valueOf(loadAF),
                "--" + INTERACTIVE_RUN_LONG_NAME + "=" + String.valueOf(interactive),
                "--" + INTERACTIVE_OUTPUT_DEL_AMPL_IMAGE_SUFFIX + "=" + INTERACTIVE_OUTPUT_DEL_AMPL_IMAGE_SUFFIX_DEFAULT_VALUE,
                "--" + INTERACTIVE_OUTPUT_SCATTER_PLOT_SUFFIX + "=" + INTERACTIVE_OUTPUT_SCATTER_PLOT_SUFFIX_DEFAULT_VALUE,
                "--" + INTERACTIVE_OUTPUT_ALLELE_FRACTION_PLOT_SUFFIX + "=" + INTERACTIVE_OUTPUT_ALLELE_FRACTION_PLOT_SUFFIX_DEFAULT_VALUE,
                "--" + INTERACTIVE_OUTPUT_COPY_RATIO_SUFFIX + "=" + INTERACTIVE_OUTPUT_COPY_RATIO_SUFFIX_DEFAULT_VALUE,
                "--" + INTERACTIVE_OUTPUT_COPY_RATIO_CLUSTERING_SUFFIX + "=" + INTERACTIVE_OUTPUT_COPY_RATIO_CLUSTERING_SUFFIX_DEFAULT_VALUE,
                "--" + OUTPUT_LOG_PREFIX_LONG_NAME + "=" + String.valueOf(logFilenamePrefix),
                "--" + NORMAL_MINOR_ALLELE_FRACTION_THRESHOLD + "=" + String.valueOf(normalMinorAlleleFractionThreshold),
                "--" + COPY_RATIO_PEAK_MIN_WEIGHT + "=" + String.valueOf(copyRatioPeakMinWeight),
                "--" + MIN_FRACTION_OF_POINTS_IN_NORMAL_ALLELE_FRACTION_REGION + "=" + String.valueOf(minFractionOfPointsInNormalAlleleFractionRegion)));

        return executor.executeScript(
                new Resource(script, CallModeledSegments.class),
                null,
                arguments);
    }
}
