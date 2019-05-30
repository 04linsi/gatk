package org.broadinstitute.hellbender.tools.walkers.haplotypecaller.STR;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.log4j.Logger;
import org.broadinstitute.hellbender.utils.GenomeLoc;
import org.broadinstitute.hellbender.utils.MathUtils;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.collections.Pair;
import org.broadinstitute.hellbender.utils.commandline.Argument;
import org.broadinstitute.hellbender.utils.contexts.ReferenceContext;
import org.broadinstitute.hellbender.utils.exceptions.GATKException;
import org.broadinstitute.hellbender.utils.exceptions.UserException;
import org.broadinstitute.hellbender.utils.genotyper.AlleleList;
import org.broadinstitute.hellbender.utils.genotyper.IndexedAlleleList;
import org.broadinstitute.hellbender.utils.genotyper.ReadLikelihoods;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * STR amplification error model.
 */
public class STRModel {

    /**
     * Key for the VCF info annotation for the Pi parameter.
     */
    public static final String PI_INFO_KEY = "STRPi";

    /**
     * Key for the VCF info annotation for the Lambda parameter.
     */
    public static final String TAU_INFO_KEY = "STRTau";


    /**
     * Key for the VCF info annotation for the Delete read count change model.
     */
    public static final String DEL_INFO_KEY = "STRDel";

    /**
     * Key for the VCF info annotation for the Insert read count change model.
     */
    public static final String INS_INFO_KEY = "STRIns";

    /**
     * Key for the VCF info annotaion for the unit string.
     */
    public static final String UNIT_INFO_KEY = "STRUnit";

    /**
     * Special STRModel instance that bypasses all sites as non-STR.
     */
    public static final STRModel NULL_MODEL = new STRModel();
    
    @Argument(doc = "file containing pre-calculated model parameters", shortName="strModel", fullName="strModelFile", required = false)
    protected File parameterCollectionFile;

    @Argument(doc = "minimum repeat total length in bp to consider a site for STR genotyping", shortName = "strMinLength", fullName = "strMinimumTotalLength", required = false)
    protected int minimumRepeatTotalLength = 10;

    @Argument(doc = "maximum repeat unit length in bp to consider a site for STR genotying", shortName = "strMaxUnit", fullName = "strMaximumUnitLength", required = false)
    protected int maximumUnitLength = 10;

    @Argument(doc = "minimum repeat count to consider a site for STR genotyping", shortName = "strMinCount", fullName = "strMinimumRepeatCount", required = false)
    protected int minimumRepeatCount = 2;

    @Argument(doc = "file use for training", shortName = "strLog", fullName = "strLogFile", required = false)
    protected File logFile;

    protected PrintWriter logWriter;

    private Logger logger = Logger.getLogger(STRModel.class);

    private STRModelCalculator[] calculators = null;


    /**
     * Composes the STRContext based on the reference context and the read likelihoods.
     * @param referenceContext the reference context at the potential STR variant site.

     * @return {@code null} if the input does not indicate the presence of a STR variant.
     */
    public STRContext composeContextFromNonVariantBlock(final ReferenceContext referenceContext, final VariantContext vc) {
        final byte[] referenceBases = referenceContext.getForwardBases();
        final byte[] windowBases = referenceContext.getBases();
        for (int unitLength = 1; unitLength <= maximumUnitLength; unitLength++) {
            if (unitLength >= referenceBases.length - 1) { // reach the end of the contig
                break;
            }
            int repeats = 1;
            final int windowOffsetPreviousRepeat = referenceContext.getLocus().getStart() - referenceContext.getWindow().getStart() - unitLength + 1;
            if (windowOffsetPreviousRepeat >= 0 && Utils.equalRange(referenceBases, 1, windowBases, windowOffsetPreviousRepeat, unitLength)) {
                continue;
            }
            for (int offset = 1 + unitLength; offset <= referenceBases.length - unitLength; offset += unitLength) {
                if (!Utils.equalRange(referenceBases, 1, referenceBases, offset, unitLength)) {
                    break;
                }
                repeats++;
            }
            if (repeats >= minimumRepeatCount && repeats * unitLength >= minimumRepeatTotalLength) {
                return composeContextFromNonVariantBlock(referenceContext, vc, unitLength);
            }
        }
        return null;
    }

    public STRContext composeContext(final ReferenceContext referenceContext) {
        final byte[] referenceBases = referenceContext.getForwardBases();
        final byte[] windowBases = referenceContext.getBases();
        for (int unitLength = 1; unitLength <= maximumUnitLength; unitLength++) {
            if (unitLength >= referenceBases.length - 1) { // reach the end of the contig
                break;
            }
            int repeats = 1;
            final int windowOffsetPreviousRepeat = referenceContext.getLocus().getStart() - referenceContext.getWindow().getStart() - unitLength + 1;
            if (windowOffsetPreviousRepeat >= 0 && Utils.equalRange(referenceBases, 1, windowBases, windowOffsetPreviousRepeat, unitLength)) {
                continue;
            }
            for (int offset = 1 + unitLength; offset <= referenceBases.length - unitLength; offset += unitLength) {
                if (!Utils.equalRange(referenceBases, 1, referenceBases, offset, unitLength)) {
                    break;
                }
                repeats++;
            }
            if (repeats >= minimumRepeatCount && repeats * unitLength >= minimumRepeatTotalLength) {
                final STRAlleleSet alleles = new STRAlleleSet(new byte[] {referenceContext.getBase()},
                        Arrays.copyOfRange(referenceBases, 1, unitLength + 1), repeats);
                return new STRContext(referenceContext.getLocus(), null, alleles, null);
            }
        }
        return null;
    }

    private STRContext composeContextFromNonVariantBlock(final ReferenceContext referenceContext, final VariantContext vc, final int unitLength) {
        final STRAlleleSet alleles = STRAlleleSet.fromReferenceBases(referenceContext.getForwardBases(), unitLength);
        final STRContext result = new STRContext(referenceContext.getLocus(), vc, alleles, null);

        return result;
    }

    /**
     * Composes the STRContext based on the reference context and the read likelihoods.
     * @param referenceContext the reference context at the potential STR variant site.
     * @param originalLikelihoods the read likelihoods for that variant.
     * @param <A> the variant allele type.
     * @return {@code null} if the input does not indicate the presence of a STR variant.
     */
    public <A extends Allele> STRContext composeContext(final ReferenceContext referenceContext, final ReadLikelihoods<A> originalLikelihoods) {
        if (referenceContext == null) {
            throw new IllegalArgumentException("the reference-context cannot be null");
        }

        final STRAlleleSet alleles = STRAlleleSet.fromAlleleList(originalLikelihoods.alleles(), referenceContext.getForwardBases());
        if (alleles == null) {
            return null;
        }

        // Filter out STR that do not fit the qualifying criteria passed by the user.
        if (!alleleSetQualifies(alleles)) {
            return null;
        }

        final Allele originalRefAllele = originalLikelihoods.alleles().stream().filter(Allele::isReference).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the input likelihoods must contain a reference allele"));
        final Map<STRAllele, List<A>> strAlleleMap = new LinkedHashMap<>(alleles.size());

        for (final STRAllele allele : alleles) {
            strAlleleMap.put(allele, new ArrayList<>(1));
        }

        for (final A allele : originalLikelihoods.alleles()) {
            final STRAllele newAllele = alleles.getByRepeatCount(alleles.referenceRepeatCount +
                    (allele.length() - originalRefAllele.length()) / alleles.getRepeatUnitLength());
            strAlleleMap.get(newAllele).add(allele);
        }

        final ReadLikelihoods<STRAllele> likelihoods = originalLikelihoods.marginalize(strAlleleMap);

        final GenomeLoc locus = referenceContext.getLocus();//.createGenomeLoc(referenceContext.getLocus().getContig(), variantSite.getStart());
        final STRContext result = new STRContext(locus, null, alleles, likelihoods);
        dumpInLogFile(result);
        return result;
    }

    public STRContext composeContext(final ReferenceContext referenceContext, final VariantContext vc) {
        if (referenceContext == null) {
            throw new IllegalArgumentException("the reference-context cannot be null");
        } else if (vc == null) {
            throw new IllegalArgumentException("the variant context cannot be null");
        } else if (vc.getAlternateAlleles().size() == 0 || vc.getAlternateAlleles().size() == 1 && vc.getAlternateAllele(0).equals(GATKVCFConstants.NON_REF_SYMBOLIC_ALLELE)) {
            return composeContextFromNonVariantBlock(referenceContext, vc);
        } else {
            return composeContextFromVariantContext(referenceContext, vc);
        }
    }

    private STRContext composeContextFromVariantContext(final ReferenceContext referenceContext, final VariantContext vc) {
        final List<Allele> originalAlleles = vc.getAlleles();

        final STRAlleleSet alleles = STRAlleleSet.fromAlleleList(originalAlleles, referenceContext.getForwardBases());
        if (alleles == null) {
            return null;
        }

        // Filter out STR that do not fit the qualifying criteria passed by the user.
        if (alleleSetQualifies(alleles)) return null;

        final Allele originalRefAllele = originalAlleles.stream().filter(Allele::isReference).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the input likelihoods must contain a reference allele"));
        final Map<STRAllele, List<Allele>> strAlleleMap = new LinkedHashMap<>(alleles.size());

        for (final STRAllele allele : alleles) {
            strAlleleMap.put(allele, new ArrayList<>(1));
        }

        for (final Allele allele : originalAlleles) {
            if (allele.isSymbolic()) {
                continue;
            }
            final STRAllele newAllele = alleles.getByRepeatCount(alleles.referenceRepeatCount +
                    (allele.length() - originalRefAllele.length()) / alleles.getRepeatUnitLength());
            strAlleleMap.get(newAllele).add(allele);
        }

        final GenomeLoc locus = referenceContext.getLocus();
        final STRContext result = new STRContext(locus, vc, alleles, null);
        int[] originalAlleleDepths = new int[originalAlleles.size()];
        for (final Genotype g : vc.getGenotypes()) {
            if (g.hasAD()) {
                originalAlleleDepths = MathUtils.addArrays(originalAlleleDepths, g.getAD());
            }
        }
        final int[] newAlleleDepths = new int[alleles.size()];
        for (int i = 0; i < newAlleleDepths.length; i++) {
            newAlleleDepths[i] = originalAlleleDepths[originalAlleles.indexOf(strAlleleMap.get(alleles.get(i)).get(0))];
        }
        result.setAlleleDepths(newAlleleDepths);
        dumpInLogFile(result);
        return result;
    }

    private boolean alleleSetQualifies(STRAlleleSet alleles) {
        if (alleles.getRepeatUnitLength() > maximumUnitLength) {
            return false;
        } else if (alleles.getMaximumRepeatCount() < minimumRepeatCount) {
            return false;
        } else if (alleles.getMaximumRepeatCount() * alleles.getRepeatUnitLength() < minimumRepeatTotalLength) {
            return false;
        }
        return true;
    }


    private void loadModelFile(final File file) {
        if (file == null) {
            calculators = new STRModelCalculator[] { NullSTRModelCalculator.INSTANCE };
        } else {
            final Map<Pair<Integer,STRModelParameter.Name>, STRModelParameter> parameters = new HashMap<>();
            int maximumRepeatUnitLengthFound = -1;
            int maximumRepeatUnitLengthDeclared = -1;
            try (final BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty() && !line.trim().startsWith("#")) {
                        final String[] parts = line.split("\t");
                        if (!parts[0].matches("^\\d.*$")) { // header?
                            if (!line.trim().equalsIgnoreCase("unit_length\tparameter\tmaximum\tintercept\trepeat_count_coef")) {
                                throw new UserException.CouldNotReadInputFile(file, "unexpected header content: " + line);
                            }
                        } else if (parts.length != 5) {
                            throw new UserException.CouldNotReadInputFile(file, "we expect 5 values per line but we found " + parts.length);
                        } else {
                            final int unitLength;
                            if (parts[0].trim().matches("^\\d+\\+$")) {
                                unitLength = Integer.parseInt(parts[0].trim().substring(0, parts[0].trim().lastIndexOf("+")));
                                if (maximumRepeatUnitLengthDeclared < 0) {
                                    maximumRepeatUnitLengthDeclared = unitLength;
                                } else if (maximumRepeatUnitLengthDeclared != unitLength) {
                                    throw new UserException.CouldNotReadInputFile(file, "there is more than one maximum unit length declared: " + maximumRepeatUnitLengthDeclared + " and " + unitLength);
                                }
                            } else if (parts[0].trim().matches("^\\d+$")) {
                                unitLength = Integer.parseInt(parts[0].trim());
                            } else {
                                throw new UserException.CouldNotReadInputFile(file, "unit_length values must be an positive integer: " + parts[0].trim());
                            }
                            if (unitLength <= 0) {
                                throw new UserException.CouldNotReadInputFile(file, "unit_length must be greater than 0:" + unitLength);
                            }
                            if (maximumRepeatUnitLengthFound < unitLength) {
                                maximumRepeatUnitLengthFound = unitLength;
                            }
                            final String parameter = parts[1].trim();
                            switch (parameter) {
                                case "ins":case "del":case "tau":case "pi": break;
                                default: throw new UserException.CouldNotReadInputFile(file, "unknown parameter: " + parameter);
                            }
                            final double[] values = new double[3];
                            try {
                                values[0] = Double.parseDouble(parts[2].trim());
                                values[1] = Double.parseDouble(parts[3].trim());
                                values[2] = Double.parseDouble(parts[4].trim());
                            } catch (final NumberFormatException ex) {
                                throw new UserException.CouldNotReadInputFile(file, "invalid double string in:" + line);
                            }
                            if (Double.isNaN(values[0]) || values[0] <= 0.0 || values[0] > 1.0) {
                                throw new UserException.CouldNotReadInputFile(file, "invalid maximum value: " + values[0]);
                            } else if (Double.isNaN(values[1]) || Double.isInfinite(values[1])) {
                                throw new UserException.CouldNotReadInputFile(file, "invalid intercept value: " + values[1]);
                            } else if (Double.isNaN(values[2]) || Double.isInfinite(values[2])) {
                                throw new UserException.CouldNotReadInputFile(file, "invalid repeat count coef value: " + values[2]);
                            }
                            final Pair<Integer, STRModelParameter.Name> key = new Pair<>(unitLength, STRModelParameter.Name.valueOf(parameter.toUpperCase()));
                            if (parameters.containsKey(key)) {
                                throw new UserException.CouldNotReadInputFile(file, "repeated entry: " + key);
                            } else {
                                parameters.put(key, new STRModelParameter(values[0], values[1], values[2]));
                            }
                        }

                    }
                }
                if (maximumRepeatUnitLengthFound < 0) {
                    throw new UserException.CouldNotReadInputFile(file, "no entries found in file");
                } else if (maximumRepeatUnitLengthDeclared >= 0 && maximumRepeatUnitLengthDeclared != maximumRepeatUnitLengthFound) {
                    throw new UserException.CouldNotReadInputFile(file, "the maximum unit length found is not the one declared with a '+'");
                } else {
                    // checking no missing entries in input.
                    for (int i = 1; i <= maximumRepeatUnitLengthFound; i++) {
                        for (final STRModelParameter.Name parameter : STRModelParameter.Name.values()) {
                            if (!parameters.containsKey(new Pair<>(i, parameter))) {
                                throw new UserException.CouldNotReadInputFile(file, "missing: " + new Pair<>(i, parameter));
                            }
                        }
                    }
                    calculators = new STRModelCalculator[maximumRepeatUnitLengthFound + 1];
                    calculators[0] = NullSTRModelCalculator.INSTANCE;
                    for (int i = 0; i <= maximumRepeatUnitLengthFound; i++) {
                        calculators[i] = new SimpleSTRModelCalculator(
                                parameters.get(new Pair<>(i, STRModelParameter.Name.PI)),
                                parameters.get(new Pair<>(i, STRModelParameter.Name.TAU)),
                                parameters.get(new Pair<>(i, STRModelParameter.Name.DEL)),
                                parameters.get(new Pair<>(i, STRModelParameter.Name.INS)));
                    }
                }
            } catch (final IOException ex) {
                throw new UserException.CouldNotReadInputFile(file, ex);
            }
        }
    }

    public final ReadLikelihoods<Allele> transformLikelihoods(final STRContext context, final Allele refAllele) {
        final List<Allele> newAlleles = context.getAlleles().stream()
                .map(a -> context.getAlleles().mapAllele(refAllele, a.getRepeatCount()))
                .collect(Collectors.toList());
        final AlleleList outputAlleles = new IndexedAlleleList<>(newAlleles);
        if (this == NULL_MODEL) {
            return context.getLikelihoods().transform(outputAlleles, MatrixUtils.createRealIdentityMatrix(outputAlleles.alleleCount()));
        } else {
            final RealMatrix transformationMatrix = log10TransformationMatrix(context);
            return context.getLikelihoods().transform(outputAlleles, transformationMatrix);
        }
    }

    /**
     * Returns a collection with the VCF annotations that may appear in a VCF when
     * applying this model.
     * @return never {@code null}
     */
    public Collection<? extends VCFHeaderLine> vcfHeaderLines() {
        //if (parameterCollectionFile != null) {
            return Arrays.asList(
                    new VCFInfoHeaderLine(STRModel.UNIT_INFO_KEY, 1, VCFHeaderLineType.Character, "STR repeat unit"));
        //} else {
        //    return Collections.emptyList();
        //}
    }

    private void dumpInLogFile(final STRContext strContext) {
        logger.debug("Found STR " + strContext);
        if (logFile != null) {
            if (logWriter == null) {
                try {
                    logWriter = new PrintWriter(new FileWriter(logFile));
                } catch (final IOException ex) {
                    throw new GATKException("Problems opening the STR log file " + logFile, ex);
                }
            }
            logWriter.println(strContext.toLongString());
        }
    }

    public STRModelCalculator forContext(final STRContext context) {
        if (calculators == null) {
            loadModelFile(this.parameterCollectionFile);
        }
        final int unitLength = context.getAlleles().getRepeatUnitLength();
        if (calculators.length <= unitLength) {
            return calculators[calculators.length - 1];
        } else {
            return calculators[unitLength];
        }
    }

    public RealMatrix log10TransformationMatrix(final STRContext context) {
        final STRModelCalculator calculator = forContext(context);
        final STRAlleleSet alleles = context.getAlleles();
        final RealMatrix result = new Array2DRowRealMatrix(alleles.alleleCount(), alleles.alleleCount());
        for (int i = 0; i < alleles.alleleCount(); i++) {
            final int iRepeatCount = alleles.get(i).repeatCount;
            result.setEntry(i, i, calculator.log10Coefficient(iRepeatCount, iRepeatCount));
            for (int j = i + 1; j < alleles.alleleCount(); j++) {
                final int jRepeatCount = alleles.get(j).repeatCount;
                result.setEntry(i, j, calculator.log10Coefficient(iRepeatCount, jRepeatCount));
                result.setEntry(j, i, calculator.log10Coefficient(jRepeatCount, iRepeatCount));
            }
        }
        return result;
    }
}