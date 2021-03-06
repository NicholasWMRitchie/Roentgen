package gov.nist.juncertainty;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.math3.distribution.MultivariateNormalDistribution;
import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.exception.OutOfRangeException;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import com.d3x.morpheus.frame.DataFrame;
import com.duckandcover.html.HTML;
import com.duckandcover.html.IToHTML;
import com.duckandcover.html.Table;
import com.duckandcover.html.Table.Item;
import com.duckandcover.html.Transforms;
import com.duckandcover.lazy.SimplyLazy;

import gov.nist.juncertainty.utility.FastIndex;
import gov.nist.microanalysis.roentgen.ArgumentException;
import gov.nist.microanalysis.roentgen.math.NullableRealMatrix;
import gov.nist.microanalysis.roentgen.swing.IValueToColor;
import gov.nist.microanalysis.roentgen.utility.BasicNumberFormat;
import gov.nist.microanalysis.roentgen.utility.HalfUpFormat;

/**
 * <p>
 * The UncertainValuesBase class serves as the base class for classes which
 * provide a array of labeled values and the associated uncertainty / covariance
 * matrix.
 * </p>
 * <p>
 * To facilitate bookkeeping, the variables within the class instanced are
 * labeled with labels derived from the generic H-class. Make sure that all the
 * classes that are used as labels implement hashCode() and equals() as the
 * label instances are stored in a {@link HashMap}. The entries in the values
 * vector and covariance matrix can be indexed by label or by integer index.
 * </p>
 * <p>
 * Covariance matrices have certain properties (square, symmetric, positive
 * definite, and the covariance elements must be related to the variances via a
 * corrolation coefficient which can take on values between -1 and 1.) These
 * properties are checked in the constructor.
 * </p>
 *
 * @author Nicholas
 * @version 1.0
 */
abstract public class UncertainValuesBase<H> //
		implements IToHTML {

	private static class CombinedUncertainValues<J> extends UncertainValuesBase<J> {

		private final List<Pair<UncertainValuesBase<? extends J>, Integer>> mIndices;

		private final SimplyLazy<RealVector> mValues = new SimplyLazy<RealVector>() {

			@Override
			protected RealVector initialize() {
				final RealVector res = new ArrayRealVector(mIndices.size());
				for (int i = 0; i < res.getDimension(); ++i) {
					final Pair<UncertainValuesBase<? extends J>, Integer> pr = mIndices.get(i);
					final int idx = pr.getSecond().intValue();
					res.setEntry(i, pr.getFirst().getEntry(idx));
				}
				return res;
			}
		};

		private final SimplyLazy<RealMatrix> mCovariances = new SimplyLazy<RealMatrix>() {

			@Override
			protected RealMatrix initialize() {
				final RealMatrix res = MatrixUtils.createRealMatrix(mIndices.size(), mIndices.size());
				for (int r = 0; r < mIndices.size(); ++r)
					for (int c = 0; c < mIndices.size(); ++c) {
						final Pair<UncertainValuesBase<? extends J>, Integer> rPr = mIndices.get(r);
						final Pair<UncertainValuesBase<? extends J>, Integer> cPr = mIndices.get(c);
						if (rPr.getFirst() == cPr.getFirst()) {
							final RealMatrix baseRes = rPr.getFirst().getCovariances();
							final int rIdx = rPr.getSecond().intValue();
							final int cIdx = cPr.getSecond().intValue();
							res.setEntry(r, c, baseRes.getEntry(rIdx, cIdx));
						}
					}
				return res;
			}

		};

		private static <J> List<J> buildInputs(final List<J> labels, //
				final boolean first) {
			if (first) {
				// Ensures labels are not duplicated.
				final FastIndex<J> res = new FastIndex<>();
				res.addMissing(labels);
				return res;
			} else
				return labels;
		}

		protected CombinedUncertainValues(final List<J> labels, //
				final List<? extends UncertainValuesBase<? extends J>> bases, //
				final boolean first //
		) throws ArgumentException {
			super(buildInputs(labels, first));
			mIndices = new ArrayList<>();
			for (final J lbl : getLabels()) {
				int cx = 0;
				for (final UncertainValuesBase<? extends J> uvb : bases) {
					final int idx = uvb.indexOf(lbl);
					if (idx >= 0) {
						mIndices.add(Pair.create(uvb, idx));
						cx++;
						break;
					}
				}
				if (cx == 0)
					throw new ArgumentException("The argument " + lbl + " has not been defined.");
			}
		}

		@Override
		public RealMatrix getCovariances() {
			return mCovariances.get();
		}

		@Override
		public RealVector getValues() {
			return mValues.get();
		}

	};

	private static class ReorderedUncertainValues<K, L extends K> //
			extends UncertainValuesBase<L> {

		private final UncertainValuesBase<K> mBase;
		private final int[] mIndexes;

		private final SimplyLazy<RealVector> mValues = new SimplyLazy<RealVector>() {

			@Override
			protected RealVector initialize() {
				final RealVector baseVals = mBase.getValues();
				final RealVector res = new ArrayRealVector(mIndexes.length);
				for (int i = 0; i < mIndexes.length; ++i)
					res.setEntry(i, baseVals.getEntry(mIndexes[i]));
				return res;
			}
		};

		private final SimplyLazy<RealMatrix> mCovariances = new SimplyLazy<RealMatrix>() {

			@Override
			protected RealMatrix initialize() {
				final RealMatrix res = MatrixUtils.createRealMatrix(mIndexes.length, mIndexes.length);
				final RealMatrix baseRes = mBase.getCovariances();
				for (int r = 0; r < mIndexes.length; ++r)
					for (int c = 0; c < mIndexes.length; ++c)
						res.setEntry(r, c, baseRes.getEntry(mIndexes[r], mIndexes[c]));
				return res;
			}
		};

		protected ReorderedUncertainValues(final List<L> labels, final UncertainValuesBase<K> base) {
			super(labels);
			mIndexes = new int[labels.size()];
			mBase = base;
			final List<L> missing = new ArrayList<>();
			for (int i = 0; i < mIndexes.length; ++i) {
				final L label = labels.get(i);
				final int idx = mBase.indexOf(label);
				if (idx < 0)
					missing.add(label);
				mIndexes[i] = idx;
			}
			assert missing.size() == 0 : //
			"The argument labels " + missing + " are missing in reorder.";
		}

		@Override
		public RealMatrix getCovariances() {
			return mCovariances.get();
		}

		@Override
		public RealVector getValues() {
			return mValues.get();
		}

	};

	private static class UVSOutOfRangeException extends OutOfRangeException {

		private static final long serialVersionUID = -8886319004986992747L;

		private final String mExtra;

		public UVSOutOfRangeException(final Number wrong, final Number low, final Number high, final String extra) {
			super(wrong, low, high);
			mExtra = extra;
		}

		@Override
		public String toString() {
			return super.toString() + " - " + mExtra;
		}

	}

	protected static final double MAX_CORR = 1.001;

	/**
	 * Builds an {@link UncertainValuesBase} object representing the specified
	 * labeled quantities as extracted from the list of {@link UncertainValuesBase}
	 * objects.
	 *
	 * @param <J>    The class implementing the labels
	 * @param labels A list of labels
	 * @param uvs    A list of {@link UncertainValuesBase} instances containing
	 *               values for each of labels
	 * @return {@link UncertainValuesBase}
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	public static <J> UncertainValues<J> build(final List<J> labels, //
			final List<UncertainValuesBase<J>> uvs //
	) throws ArgumentException {
		// Test that each requested label is defined once and only once.
		for (final J label : labels) {
			int count = 0;
			for (final UncertainValuesBase<? extends J> uv : uvs)
				if (uv.hasLabel(label))
					count++;
			if (count < 1)
				throw new ArgumentException(
						"The label " + label + " is not defined in one of the input UncertainValues.");
			if (count > 1)
				throw new ArgumentException(
						"The label " + label + " is muliply defined in one of the input UncertainValues.");
		}
		final UncertainValues<J> res = new UncertainValues<>(labels);
		for (final UncertainValuesBase<J> uv : uvs)
			for (final J label1 : labels)
				if (uv.hasLabel(label1)) {
					res.set(label1, uv.getEntry(label1), uv.getVariance(label1));
					for (final J label2 : uv.getLabels())
						if (res.hasLabel(label2)) {
							final double cv = uv.getCovariance(label1, label2);
							if (cv != 0.0)
								res.setCovariance(label1, label2, cv);
						}
				}
		return res;
	}

	/**
	 * Combines a disjoint set of {@link UncertainValues} into a single one.
	 * (Disjoint meaning not sharing a common label.)
	 *
	 * @param <J>       The class implementing the labels
	 * @param uvs       List&lt;UncertainValues&gt;
	 * @param takeFirst if false fails if there are duplicate definitions for any
	 *                  label
	 * @return {@link UncertainValues}
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	public static <J> UncertainValuesBase<J> combine(final List<? extends UncertainValuesBase<? extends J>> uvs, //
			final boolean takeFirst//
	) throws ArgumentException {
		// Test that each requested label is defined once and only once.
		final List<J> labels = new ArrayList<>();
		for (final UncertainValuesBase<? extends J> uv : uvs)
			labels.addAll(uv.getLabels());
		return new CombinedUncertainValues<>(labels, uvs, takeFirst);
	}

	/**
	 * Creates a bitmap that represents the difference between uncertainties
	 * associated with these two sets of UncertainValuesBase. The difference between
	 * the covariances is plotted.
	 *
	 * @param <J>    The class implementing the labels
	 * @param uvs1   First {@link UncertainValuesBase} object to compare
	 * @param uvs2   Second {@link UncertainValuesBase} object to compare
	 * @param corr   Map from value to color
	 * @param pixDim Size on edge of each block representing a single value
	 * @return BufferedImage The resulting image
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	public static <J> BufferedImage compareAsBitmap(final UncertainValuesBase<J> uvs1, //
			final UncertainValuesBase<J> uvs2, //
			final IValueToColor corr, //
			final int pixDim //
	) throws ArgumentException {
		final List<J> common = new ArrayList<J>();
		common.addAll(uvs1.getLabels());
		common.retainAll(uvs2.getLabels());
		final int dim = common.size();
		final boolean[] disp = new boolean[dim];
		final BufferedImage bi = new BufferedImage(pixDim * (dim + 2), pixDim * dim, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g2 = bi.createGraphics();
		g2.setColor(Color.WHITE);
		g2.fillRect(0, 0, bi.getWidth(), bi.getHeight());
		for (int rr = 0; rr < dim; ++rr) {
			final J label = common.get(rr);
			final double v1 = uvs1.getEntry(label), v2 = uvs2.getEntry(label);
			// Red if values are substantially different
			if ((Math.abs(v1 - v2) > 0.01 * Math.max(v1, v2)) && (Math.max(v1, v2) > 1.0e-10))
				g2.setColor(Color.RED);
			else
				g2.setColor(Color.white);
			g2.fillRect(0, rr * pixDim, pixDim, pixDim);
			final double dv1 = uvs1.getUncertainty(label), dv2 = uvs2.getUncertainty(label);
			if ((dv1 < 1.0e-6 * Math.abs(v1)) && (dv2 < 1.0e-6 * Math.abs(v2))) {
				g2.setColor(Color.WHITE);
				disp[rr] = false;
			} else {
				disp[rr] = true;
				final double delta = 0.5 * Math.abs((dv1 - dv2) / (dv1 + dv2));
				g2.setColor(corr.map(delta));
			}
			g2.fillRect((2 + rr) * pixDim, rr * pixDim, pixDim, pixDim);
		}
		for (int rr = 0; rr < dim; ++rr) {
			final J rLabel = common.get(rr);
			if (disp[rr]) {
				for (int cc = 0; cc < dim; ++cc) {
					final J cLabel = common.get(cc);
					if (disp[cc]) {
						final double cc1 = uvs1.getCorrelationCoefficient(rLabel, cLabel);
						final double cc2 = uvs2.getCorrelationCoefficient(rLabel, cLabel);
						final double delta = 0.5 * Math.abs(cc1 - cc2);
						g2.setColor(corr.map(delta));
						g2.fillRect((2 + rr) * pixDim, rr * pixDim, pixDim, pixDim);
					}
				}
			}
		}
		return bi;
	}

	public static <J> UncertainValues<J> forceMinCovariance(final UncertainValuesBase<J> vals, //
			final RealVector minCov) {
		assert vals.getDimension() == minCov.getDimension();
		final RealMatrix cov = vals.getCovariances().copy();
		for (int rc = 0; rc < vals.getDimension(); ++rc)
			if (cov.getEntry(rc, rc) < minCov.getEntry(rc))
				cov.setEntry(rc, rc, minCov.getEntry(rc));
		return new UncertainValues<J>(vals.getLabels(), vals.getValues(), cov);
	}

	/**
	 * Returns the UncertainValues that result from applying the function/Jacobian
	 * in <code>nmjf</code> to the input values/variances in <code>input</code>.
	 *
	 *
	 * @param <J>   The class implementing the labels
	 * @param emm   The model
	 * @param input Input value
	 * @return {@link UncertainValuesCalculator} A calculator for the result
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	public static <J> UncertainValuesCalculator<J> propagateAnalytical(
			final ExplicitMeasurementModel<? extends J, ? extends J> emm, //
			final UncertainValuesBase<J> input //
	) throws ArgumentException {
		return new UncertainValuesCalculator<J>(emm, input);
	}

	/**
	 * Returns the {@link UncertainValuesCalculator} that will propagate uncertainty
	 * using the analyical Jacobian algorithm.
	 *
	 *
	 * @param <J>   The class implementing the labels
	 * @param emm   The model
	 * @param input The input data
	 * @param dinp  The delta to apply to the input values
	 * @return {@link UncertainValuesCalculator}
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	public static <J> UncertainValuesCalculator<J> propagateFiniteDifference(
			final ExplicitMeasurementModel<? extends J, ? extends J> emm, //
			final UncertainValuesBase<J> input, //
			final RealVector dinp //
	) throws ArgumentException {
		final UncertainValuesCalculator<J> res = new UncertainValuesCalculator<J>(emm, input);
		final RealVector reordered = new ArrayRealVector(res.getInputDimension());
		for (int i = 0; i < res.getInputDimension(); ++i) {
			final int nidx = emm.inputIndex(res.getLabel(i));
			if (nidx != -1)
				reordered.setEntry(i, dinp.getEntry(nidx));
		}
		res.setCalculator(res.new FiniteDifference(reordered));
		return res;
	}

	/**
	 * Returns the {@link UncertainValuesCalculator} that will propagate uncertainty
	 * using the finite difference algorithm and a step size based of the input
	 * values times a constant multiplier.
	 *
	 * @param <J>   The class implementing the labels
	 * @param emm   The model
	 * @param input The input data
	 * @param frac  Computes dinp, the fractional difference size as
	 *              getInputValues().mapMultiply(frac)
	 * @return {@link UncertainValuesCalculator}
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	public static <J> UncertainValuesCalculator<J> propagateFiniteDifference(
			final ExplicitMeasurementModel<? extends J, ? extends J> emm, //
			final UncertainValuesBase<J> input, //
			final double frac //
	) throws ArgumentException {
		final UncertainValuesCalculator<J> res = new UncertainValuesCalculator<J>(emm, input);
		res.setCalculator(res.new FiniteDifference(frac));
		return res;
	}

	/**
	 * Returns the {@link UncertainValuesCalculator} that will propagate uncertainty
	 * using the Monte Carlo algorithm and a default
	 * {@link MultivariateNormalDistribution}.
	 *
	 * @param <J>    The class implementing the labels
	 * @param emm    The model
	 * @param input  The input data
	 * @param nEvals The number of times to evaluate the model
	 * @return {@link UncertainValuesCalculator}
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	public static <J> UncertainValuesCalculator<J> propagateMonteCarlo(
			final ExplicitMeasurementModel<? extends J, ? extends J> emm, //
			final UncertainValuesBase<J> input, //
			final int nEvals //
	) throws ArgumentException {
		final UncertainValuesCalculator<J> res = new UncertainValuesCalculator<J>(emm, input);
		res.setCalculator(res.new MonteCarlo(nEvals));
		return res;
	}

	/**
	 * Tests exact equality
	 *
	 * @param <J>  The label class
	 * @param uvs1 The first {@link UncertainValuesBase} object to test
	 * @param uvs2 The second {@link UncertainValuesBase} object to test
	 * @return boolean, true if exactly the same value-by-value and
	 *         covariance-by-covariance
	 */
	public static <J> boolean testEquality(final UncertainValuesBase<J> uvs1, //
			final UncertainValuesBase<J> uvs2) {
		if (uvs1.getDimension() != uvs2.getDimension())
			return false;
		for (final J label1 : uvs1.getLabels()) {
			if (uvs1.getEntry(label1) != uvs2.getEntry(label1))
				return false;
			for (final J label2 : uvs1.getLabels())
				if (uvs1.getCovariance(label1, label2) != uvs2.getCovariance(label1, label2))
					return false;
		}
		return true;
	}

	/**
	 * Tests equality to within the tolerance eps
	 *
	 * @param <J>  The label class
	 * @param uvs1 The first {@link UncertainValuesBase} object to test
	 * @param uvs2 The second {@link UncertainValuesBase} object to test
	 * @param eps  The tolerance
	 * @return boolean, true if exactly the same value-by-value and
	 *         covariance-by-covariance
	 */
	public static <J> boolean testSimiliarity(final UncertainValuesBase<J> uvs1, //
			final UncertainValuesBase<J> uvs2, //
			final double eps) {
		if (uvs1.getDimension() != uvs2.getDimension())
			return false;
		for (final J label1 : uvs1.getLabels()) {
			final double d = Math.abs(uvs1.getEntry(label1) - uvs2.getEntry(label1));
			final double m = Math.max(uvs1.getEntry(label1), uvs2.getEntry(label1));
			if ((d / m > eps) && (d > eps)) {
				System.err.println(label1 + "  " + d + "/" + m);
				return false;
			}
			for (final J label2 : uvs1.getLabels())
				if (!label1.equals(label2)) {
					final double dc = Math.abs(uvs1.getCovariance(label1, label2) - uvs2.getCovariance(label1, label2));
					final double mc = Math.max(uvs1.getCovariance(label1, label2), uvs2.getCovariance(label1, label2));
					if ((dc / mc > eps) && (dc > eps)) {
						System.err.println(label1 + "  " + label2 + " " + dc + "/" + mc);
						return false;
					}
				}
		}
		return true;
	}

	/**
	 * Return an {@link UncertainValues} object with the same dimension and values
	 * as input except all the covariances except those associated with label are
	 * zeroed.
	 *
	 * @param <J>   The class implementing the labels
	 * @param label The label to leave unmodified
	 * @param input The input {@link UncertainValuesBase} object
	 * @return {@link UncertainValues}
	 */
	public static <J> UncertainValues<J> zeroBut(final J label, final UncertainValuesBase<J> input) {
		final UncertainValues<J> res = new UncertainValues<J>(input.getLabels(), input.getValues(), 0.0);
		final int idx = input.indexOf(label);
		res.setCovariance(idx, idx, input.getCovariance(idx, idx));
		return res;
	}

	/**
	 * Checks that the covariance matrix is m x m, the variances are non-negative,
	 * the covariances are bounded correlation coefficients between -1<r<1.
	 *
	 * @param m      Dimension
	 * @param covar  The covariance matrix
	 * @param maxVal The max
	 */
	private static void validateCovariance(final int m, //
			final RealMatrix covar, //
			final double maxVal) {
		if (covar.getRowDimension() != m)
			throw new DimensionMismatchException(covar.getRowDimension(), m);
		if (covar.getColumnDimension() != m)
			throw new DimensionMismatchException(covar.getColumnDimension(), m);
		final double EPS = 1.0e-10, SREPS = Math.sqrt(EPS);
		for (int r = 0; r < covar.getRowDimension(); ++r) {
			final double entryRR = covar.getEntry(r, r);
			if (entryRR < 0.0) {
				// This is necessary because of subtle rounding errors
				if (entryRR > -EPS * maxVal)
					covar.setEntry(r, r, 0.0);
				else
					throw new UVSOutOfRangeException(entryRR, 0.0, Double.MAX_VALUE, "Row=" + r);
			}
		}
		for (int r = 0; r < covar.getRowDimension(); ++r)
			for (int c = r + 1; c < covar.getColumnDimension(); ++c) {
				final double entryRC = covar.getEntry(r, c);
				if (Math.abs(entryRC - covar.getEntry(c, r)) > SREPS * Math.abs(entryRC) + EPS)
					throw new OutOfRangeException(entryRC, covar.getEntry(c, r) - EPS, covar.getEntry(c, r) + EPS);
				final double max = Math.sqrt(covar.getEntry(c, c) * covar.getEntry(r, r)) + EPS;
				final double rr = entryRC / max;
				if ((rr > MAX_CORR) || (rr < -MAX_CORR)) {
					if (Math.abs(entryRC) > EPS)
						throw new UVSOutOfRangeException(entryRC, -max, max, "Row=" + r + ", Col=" + c);
					else {
						covar.setEntry(r, c, Math.signum(rr) * max);
						covar.setEntry(c, r, Math.signum(rr) * max);
					}
				}
			}
	}

	private final List<H> mLabels;

	/**
	 * Constructs a UncertainValuesBase object based on the specified labels with
	 * zero values and NaN covariances.
	 *
	 * @param labels List&lt;Object&gt; A list of objects implementing hashCode()
	 *               and equals().
	 */
	protected UncertainValuesBase(final List<H> labels //
	) {
		mLabels = new FastIndex<>(labels);
	}

	/**
	 * Renders a bitmapped representation of this {@link UncertainValuesBase}
	 * object.
	 *
	 * @param dim   The size on edge of the square representing a value
	 * @param sigma Maps values to colors
	 * @param corr  Maps correlation coefficients to colors
	 * @return BufferedImage
	 */
	public BufferedImage asCovarianceBitmap(final int dim, //
			final IValueToColor sigma, //
			final IValueToColor corr) {
		final RealMatrix sc = NullableRealMatrix.build(getDimension(), getDimension());
		final RealVector values = getValues();
		for (int r = 0; r < getDimension(); ++r) {
			final double crr = getCovariance(r, r);
			final double rVal = values.getEntry(r);
			sc.setEntry(r, r, Math.sqrt(crr) / rVal);
			if (Math.sqrt(crr) > 1.0e-8 * Math.abs(rVal)) {
				for (int c = r + 1; c < getDimension(); ++c) {
					final double ccc = getCovariance(c, c);
					if (Math.sqrt(ccc) > 1.0e-8 * Math.abs(values.getEntry(c))) {
						final double rr = getCovariance(r, c) / (Math.sqrt(crr * ccc));
						sc.setEntry(r, c, rr);
						sc.setEntry(c, r, rr);
					}
				}
			}
		}
		final BufferedImage bi = new BufferedImage(dim * getDimension(), dim * getDimension(),
				BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g2 = bi.createGraphics();
		g2.setColor(Color.WHITE);
		g2.fillRect(0, 0, bi.getWidth(), bi.getHeight());
		for (int r = 0; r < getDimension(); ++r) {
			g2.setColor(sigma.map(Math.sqrt(sc.getEntry(r, r))));
			g2.fillRect(r * dim, r * dim, dim, dim);
			for (int c = r + 1; c < getDimension(); ++c) {
				final double entry = sc.getEntry(r, c);
				if (!Double.isNaN(entry))
					g2.setColor(corr.map(entry));
				else
					g2.setColor(Color.yellow);
				g2.fillRect(c * dim, r * dim, dim, dim);
				g2.fillRect(r * dim, c * dim, dim, dim);
			}
		}
		return bi;
	}

	/**
	 * Renders a bitmapped representation of this {@link UncertainValuesBase}
	 * object.
	 *
	 * @param dim   The size on edge of the square representing a value
	 * @param sigma Maps values to colors
	 * @param corr  Maps correlation coefficients to colors
	 * @return BufferedImage
	 */
	public BufferedImage asCovarianceBitmap(final int dim, //
			final IValueToColor sigma, //
			final IValueToColor corr, //
			final boolean labeled) {
		if (!labeled)
			return asCovarianceBitmap(dim, sigma, corr);
		else {
			final RealMatrix sc = NullableRealMatrix.build(getDimension(), getDimension());
			final RealVector values = getValues();
			for (int r = 0; r < getDimension(); ++r) {
				final double crr = getCovariance(r, r);
				final double rVal = values.getEntry(r);
				sc.setEntry(r, r, Math.sqrt(crr) / rVal);
				if (Math.sqrt(crr) > 1.0e-8 * Math.abs(rVal)) {
					for (int c = r + 1; c < getDimension(); ++c) {
						final double ccc = getCovariance(c, c);
						if (Math.sqrt(ccc) > 1.0e-8 * Math.abs(values.getEntry(c))) {
							final double rr = getCovariance(r, c) / (Math.sqrt(crr * ccc));
							sc.setEntry(r, c, rr);
							sc.setEntry(c, r, rr);
						}
					}
				}
			}
			final List<H> labels = getLabels();
			int extra = 0;
			{
				final BufferedImage tbi = new BufferedImage(dim * getDimension() + extra, dim * getDimension() + extra,
						BufferedImage.TYPE_3BYTE_BGR);
				final Graphics2D g2 = tbi.createGraphics();
				FontMetrics fm = g2.getFontMetrics();
				if (fm.getHeight() < dim) {
					final Font newF = g2.getFont().deriveFont((float) (0.8 * dim));
					g2.setFont(newF);
					fm = g2.getFontMetrics();
				}
				for (int r = 0; r < labels.size(); ++r) {
					final String lbl = labels.get(r).toString();
					// Rectangle2D rect=fm.getStringBounds(lbl, g2);
					extra = Math.max(fm.stringWidth(lbl + "WWWW"), extra);
				}
			}

			final BufferedImage bi = new BufferedImage(dim * getDimension() + extra, dim * getDimension() + extra,
					BufferedImage.TYPE_3BYTE_BGR);
			final Graphics2D g2 = bi.createGraphics();
			FontMetrics fm = g2.getFontMetrics();
			if (fm.getHeight() < dim) {
				final Font newF = g2.getFont().deriveFont((float) (0.8 * dim));
				g2.setFont(newF);
				fm = g2.getFontMetrics();
			}

			g2.setColor(Color.WHITE);
			g2.fillRect(0, 0, bi.getWidth(), bi.getHeight());
			for (int r = 0; r < getDimension(); ++r) {
				final String lbl = labels.get(r).toString();
				// Rectangle2D rect=fm.getStringBounds(lbl, g2);
				g2.setColor(Color.darkGray);
				g2.drawString(lbl, extra - fm.stringWidth(lbl + "W"), (r + 1) * dim - fm.getDescent() + extra);
				g2.setColor(sigma.map(Math.sqrt(sc.getEntry(r, r))));
				g2.fillRect(r * dim + extra, r * dim + extra, dim, dim);
				g2.drawLine(extra - 5, (r + 1) * dim + extra, extra, (r + 1) * dim + extra);
				for (int c = r + 1; c < getDimension(); ++c) {
					final double entry = sc.getEntry(r, c);
					if (!Double.isNaN(entry))
						g2.setColor(corr.map(entry));
					else
						g2.setColor(Color.yellow);
					g2.fillRect(c * dim + extra, r * dim + extra, dim, dim);
					g2.fillRect(r * dim + extra, c * dim + extra, dim, dim);
				}
			}
			int w = fm.stringWidth("W");
			for (int i = w; i < extra - 2*w; ++i) {
				double v = -1.0 + 2.0 * (double) (i - w) / (double) (extra - 3 * w);
				g2.setColor(corr.map(v));
				g2.fillRect(w, w + i, w, 1);
			}
			g2.setColor(Color.darkGray);
			g2.drawString("+1.0 (correlated)", 3 * w, w + fm.getHeight());
			g2.drawString(" 0.0 (uncorrelated)", 3 * w, w + (extra - 2 * w) / 2 + fm.getHeight());
			g2.drawString("-1.0 (anti-correlated)", 3 * w, extra - 2*w + fm.getHeight());

			final AffineTransform tr = g2.getTransform();
			tr.quadrantRotate(3);
			g2.setTransform(tr);
			g2.setColor(Color.darkGray);
			for (int c = 0; c < getDimension(); ++c) {
				final String lbl = labels.get(c).toString();
				// Rectangle2D rect=fm.getStringBounds(lbl, g2);
				g2.drawString(lbl, -extra + fm.stringWidth("W"), extra + (c + 1) * dim - fm.getDescent());
			}
			return bi;
		}

	}

	/**
	 * Check that the indices are valid and not repeated. Uses assert rather than an
	 * Exception.
	 *
	 * @param indices An array of indices
	 * @return true
	 */
	public boolean assertIndices(final int[] indices) {
		for (int i = 0; i < indices.length; ++i) {
			assert indices[i] >= 0 : "Index[" + i + "] is less than zero.";
			assert indices[i] < mLabels.size() : "Index[" + i + "] is larger than the number of labels.";
			for (int j = i + 1; j < indices.length; ++j)
				assert indices[i] != indices[j] : "Duplicated index: Index[" + i + "] equals Index[" + j + "]";
		}
		return true;
	}

	/**
	 * Takes the input UncertainValues and creates a new, reordered UncertainValues
	 * object in which the correlated rows/columns are grouped together. THe
	 * non-zero covariances are moved away from the edges and upper-right and
	 * lower-left corners towards the diagonal.
	 *
	 *
	 * @return {@link UncertainValuesBase}
	 */

	public UncertainValuesBase<H> blockDiagnonalize() {
		final int[] count = new int[getDimension()];
		for (int r = 0; r < count.length; ++r)
			for (int c = 0; c < count.length; ++c)
				if ((r != c) && (getCovariance(r, c) != 0.0))
					++count[r];
		final int[] idx = new int[getDimension()];
		final boolean[] done = new boolean[getDimension()];
		Arrays.fill(done, false);
		int next = 0;
		for (int covs = 0; (next < count.length) && (covs < count.length); ++covs) {
			for (int r = 0; (next < count.length) && (r < count.length); ++r) {
				if ((!done[r]) && (count[r] == covs)) {
					final List<Integer> cols = new ArrayList<>();
					idx[next] = r;
					++next;
					done[r] = true;
					cols.add(r);
					while (!cols.isEmpty()) {
						final int col = cols.remove(0);
						done[col] = true;
						for (int covs2 = covs; covs2 < count.length; ++covs2) {
							for (int rr = 0; rr < done.length; ++rr)
								if ((!done[rr]) && (count[rr] == covs2) && (getCovariance(rr, col) != 0.0)) {
									idx[next] = rr;
									++next;
									done[rr] = true;
									cols.add(rr);
								}
						}
					}
				}
			}
		}
		assert next == count.length;
		try {
			return extract(idx);
		} catch (final ArgumentException e) {
			System.err.println("Should never happen!");
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final UncertainValuesBase<?> other = (UncertainValuesBase<?>) obj;
		return Objects.equals(mLabels, other.getLabels());
	}

	/**
	 * Tests the equality of the values, variances and correlation coefficients to
	 * within the fractional tolerance.
	 *
	 * @param uv2 The other {@link UncertainValuesBase} to compare values and
	 *            covariances against
	 * @param tol The tolerance
	 * @return boolean true if meets tolerance
	 */
	public boolean equals(final UncertainValuesBase<H> uv2, //
			final double tol) {
		for (int r = 0; r < this.getDimension(); ++r) {
			final double tmp = Math.abs((getEntry(r) - uv2.getEntry(mLabels.get(r))) / getEntry(r));
			if (Double.isFinite(tmp))
				if (tmp > tol)
					return false;
			final double var = Math.abs((getVariance(r) - getVariance(mLabels.get(r))) / getEntry(r));
			if (Double.isFinite(var))
				if (var > tol)
					return false;
			for (int c = r; c < this.getDimension(); ++c)
				if (Math.abs(getCorrelationCoefficient(r, c)
						- uv2.getCorrelationCoefficient(mLabels.get(r), mLabels.get(c))) > tol)
					return false;
		}
		return true;
	}

	/**
	 * Creates a new {@link UncertainValues} object representing only those
	 * rows/columns whose indexes are in idx. Can be used to create a sub-set of
	 * this {@link UncertainValues} or reorder this {@link UncertainValues}.
	 *
	 * @param indices An array of indices
	 * @return {@link UncertainValues} A new object
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	final public UncertainValuesBase<H> extract(final int[] indices) throws ArgumentException {
		return extract(getLabels(indices));
	}

	/**
	 * Creates a new {@link UncertainValues} object representing only those
	 * rows/columns whose indexes are in idx. Can be used to create a sub-set of
	 * this {@link UncertainValues} or reorder this {@link UncertainValues}.
	 *
	 * @param <K>    The class implementing the labels
	 * @param labels A {@link List} of labels
	 * @return {@link UncertainValues} A new object
	 * @throws ArgumentException When there is an inconsistency in the function
	 *                           arguments
	 */
	final public <K extends H> UncertainValuesBase<K> extract(final List<K> labels) throws ArgumentException {
		return reorder(labels);
	}

	/**
	 * Extract an matrix of covariances from this UncertainValue object for the
	 * specified list of labels in the order specified by the label list.
	 *
	 * @param labels List&lt;H&gt;
	 * @return RealMatrix
	 */
	final public RealMatrix extractCovariances(final List<H> labels) {
		final RealMatrix res = MatrixUtils.createRealMatrix(labels.size(), labels.size());
		for (int r = 0; r < labels.size(); ++r) {
			final int ridx = indexOf(labels.get(r));
			res.setEntry(r, r, getCovariance(r, r));
			for (int c = r + 1; c < labels.size(); ++c) {
				final int cidx = indexOf(labels.get(c));
				final double cv = getCovariance(ridx, cidx);
				res.setEntry(r, c, cv);
				res.setEntry(c, r, cv);
			}
		}
		return res;
	}

	/**
	 * Extract an array of values from this UncertainValue object for the specified
	 * list of labels in the order specified by the label list.
	 *
	 * @param labels List&lt;H&gt;
	 * @return RealVector
	 */
	final public RealVector extractValues(final List<? extends H> labels) {
		final RealVector res = new ArrayRealVector(labels.size());
		int i = 0;
		for (final H label : labels) {
			assert indexOf(label) != -1 : label + " is missing in extractValues(...)";
			res.setEntry(i, getEntry(label));
			++i;
		}
		return res;
	}

	/**
	 * Returns the covariance associated with the specific labels.
	 *
	 * @param p1 H - Label one
	 * @param p2 H - Label 2
	 * @return double The correlation coefficient associated with indices p1 and p2
	 */
	final public double getCorrelationCoefficient(final H p1, //
			final H p2) {
		return getCorrelationCoefficient(indexOf(p1), indexOf(p2));
	}

	/**
	 * Returns the covariance associated with the specific integer indices.
	 *
	 * @param p1 int
	 * @param p2 int
	 * @return double The correlation coefficient associated with indices p1 and p2
	 */
	final public double getCorrelationCoefficient(final int p1, //
			final int p2) {
		final RealMatrix cov = getCovariances();
		final double c12 = cov.getEntry(p1, p2);
		if (c12 == 0.0)
			return 0.0;
		else {
			final double c11 = cov.getEntry(p1, p1);
			final double c22 = cov.getEntry(p2, p2);
			if (c11 * c22 > 0) {
				final double rho = c12 / Math.sqrt(c11 * c22);
				assert (rho > -1.00000001) && (rho < 1.00000001) : rho;
				return Math.min(1.0, Math.max(-1.0, rho));
			} else
				return Double.NaN;
		}
	}

	/**
	 * Returns a RealMatrix filled with the correlation coefficient values.
	 *
	 * @return RealMatrix
	 */
	final public RealMatrix getCorrelationCoefficients() {
		final int dim = getDimension();
		final RealMatrix rm = MatrixUtils.createRealMatrix(dim, dim);
		for (int r = 0; r < dim; ++r)
			for (int c = r + 1; c < dim; ++c) {
				final double cc = getCorrelationCoefficient(r, c);
				rm.setEntry(r, c, cc);
				rm.setEntry(c, r, cc);
			}
		return rm;
	}

	/**
	 * Returns the covariance associated with the specific labels
	 *
	 * @param label1 H
	 * @param label2 H
	 * @return double The covariance associated with labels label1 and label2 or 0
	 *         if one or both labels unknown
	 */
	final public double getCovariance(final H label1, //
			final H label2) {
		final int p1 = indexOf(label1), p2 = indexOf(label2);
		return (p1 != -1) && (p2 != -1) ? getCovariance(p1, p2) : 0.0;
	}

	/**
	 * Returns the covariance associated with the specific integer indices.
	 *
	 * @param p1 int
	 * @param p2 int
	 * @return double The covariance associated with indices p1 and p2
	 */
	final public double getCovariance(final int p1, //
			final int p2) {
		return getCovariances().getEntry(p1, p2);
	}

	/**
	 * Returns a RealMatrix of the variance and covariances associated with the
	 * specified list of labels.
	 *
	 * @param labels A {@link List} of labels
	 * @return Map&lt;H, Double&gt;
	 */
	final public RealMatrix getCovariances(final List<H> labels) {
		final int[] idx = new int[labels.size()];
		for (int i = 0; i < idx.length; ++i)
			idx[i] = indexOf(labels.get(i));
		final RealMatrix res = MatrixUtils.createRealMatrix(idx.length, idx.length);
		final RealMatrix cov = getCovariances();
		for (int i = 0; i < idx.length; ++i)
			for (int j = 0; j < idx.length; ++j) {
				res.setEntry(i, j, cov.getEntry(idx[i], idx[j]));
			}
		return res;
	}

	/**
	 * Returns the number of labels which is equivalent to the number of values and
	 * row/columns in the covariance matrix.
	 *
	 * @return int
	 */
	final public int getDimension() {
		return getLabels().size();
	}

	/**
	 * Returns the value associated with the entry associated with label.
	 *
	 * @param label Object
	 * @return double
	 */
	final public double getEntry(final H label) {
		final int p = indexOf(label);
		assert p >= 0 : "Label " + label.toString() + " missing.";
		return getValues().getEntry(p);
	}

	/**
	 * Returns the function value associated with the label specified by index.
	 *
	 * @param p integer index
	 * @return double
	 */
	final public double getEntry(final int p) {
		return getValues().getEntry(p);
	}

	/**
	 * Returns the label at the p-th entry in the values vector and in the p-th row
	 * and column in the covariance matrix.
	 *
	 * @param p The integer index
	 * @return Object
	 */
	final public H getLabel(final int p) {
		return getLabels().get(p);
	}

	/**
	 * Returns an unmodifiable, ordered list of the Object labels associated with
	 * the values and covariances in this object.
	 *
	 * @return List&lt;Object&gt;
	 */
	final public List<H> getLabels() {
		return Collections.unmodifiableList(mLabels);
	}

	/**
	 * Extracts all labels assignable as cls
	 *
	 * @param <T>          The class implementing the labels
	 * @param cls&lt;T&gt; The class type
	 * @return List&lt;T&gt;
	 */
	final public <T> List<T> getLabels(final Class<T> cls) {
		final List<T> res = new ArrayList<>();
		for (final Object tag : getLabels())
			if (cls.isInstance(tag))
				res.add(cls.cast(tag));
		return Collections.unmodifiableList(res);
	}

	/**
	 * Returns the uncertainty associated with the specific label
	 *
	 * @param label H The label
	 * @return double The uncertainty associated with label or 0.0 if label unknown
	 */
	final public double getUncertainty(final H label) {
		final int p = indexOf(label);
		return p != -1 ? Math.sqrt(getCovariance(p, p)) : 0.0;
	}

	/**
	 * Returns the uncertainty associated with the specific index
	 *
	 * @param p int The integer index
	 * @return double The uncertainty associated with index p
	 */
	final public double getUncertainty(final int p) {
		return Math.sqrt(getCovariance(p, p));
	}

	/**
	 * Returns a map from label to the associated {@link UncertainValue} as returned
	 * by getUncertainValue(..).
	 *
	 * @return Map&lt;H,UncertainValue&gt;
	 */
	final public Map<H, UncertainValue> getUncertainValueMap() {
		final Map<H, UncertainValue> res = new HashMap<>();
		for (final H label : getLabels())
			res.put(label, getUncertainValue(label));
		return res;
	}

	/**
	 * Returns an UncertainValue for the quantity assoicated with the specified
	 * label. The value is the same as getEntry(...) and the uncertainty is the
	 * on-diagonal covariance matrix entry associated with that entry.
	 *
	 * @param label The label
	 * @return UncertanValue
	 */
	final public UncertainValue getUncertainValue(final H label) {
		return new UncertainValue(getEntry(label), Math.sqrt(getVariance(label)));
	}

	/**
	 * Gets a map from label to double of all the values in this
	 * {@link UncertainValuesBase} object.
	 *
	 * @return Map&lt;H,Double&gt;
	 */
	final public Map<H, Double> getValueMap() {
		return getValueMap(getLabels());
	}

	/**
	 * Gets a map from label to double of all the values with labels of class T in
	 * this {@link UncertainValuesBase} object.
	 *
	 * @param <T> The label class
	 * @param cls A Class&lt;T&gt; object
	 * @return Map&lt;H,Double&gt;
	 */
	final public <T> Map<T, Double> getValueMap(final Class<T> cls) {
		final Map<T, Double> res = new HashMap<>();
		for (final H label : getLabels())
			if (cls.isInstance(label))
				res.put(cls.cast(label), getEntry(label));
		return res;
	}

	/**
	 * Gets a map from label to double of all the values in this
	 * {@link UncertainValuesBase} object in the specified list.
	 *
	 * @param labels A list of labels
	 * @return Map&lt;H,Double&gt;
	 */
	final public Map<H, Double> getValueMap(final List<? extends H> labels) {
		final Map<H, Double> res = new HashMap<>();
		for (final H label : labels)
			res.put(label, getEntry(label));
		return res;
	}

	/**
	 * Returns a {@link RealVector} containing the values associated with this
	 * object in the order specified by the List labels.
	 *
	 * @param labels A {@link List} of labels to extract
	 * @return {@link RealVector}
	 */
	final public RealVector getValues(final List<? extends H> labels) {
		final RealVector res = new ArrayRealVector(labels.size());
		for (int i = 0; i < res.getDimension(); ++i)
			res.setEntry(i, getValues().getEntry(indexOf(labels.get(i))));
		return res;
	}

	/**
	 * Returns the variance associated with the specific label
	 *
	 * @param label H
	 * @return double The variance associated with label
	 */
	final public double getVariance(final H label) {
		final int p = indexOf(label);
		return getCovariance(p, p);
	}

	/**
	 * Returns the variance associated with the specific index
	 *
	 * @param idx int
	 * @return double The variance associated with label
	 */
	final public double getVariance(final int idx) {
		return getCovariance(idx, idx);
	}

	/**
	 * Is there a value and covariances associated with the specified label?
	 *
	 * @param label A label
	 * @return boolean
	 */
	final public boolean hasLabel(final Object label) {
		return getLabels().indexOf(label) != -1;
	}

	/**
	 * Returns a list of those labels in desired that are not in the labels
	 * associated with this.
	 *
	 * @param desired A {@link List} of desired labels
	 * @return List&lt;H&gt;
	 */
	public List<H> missing(final List<? extends H> desired) {
		final List<H> res = new ArrayList<>();
		for (final H lbl : desired)
			if (!hasLabel(lbl))
				res.add(lbl);
		return res;
	}

	/**
	 * Returns the index associated with the specified label or -1 if not found.
	 *
	 * @param label A label
	 * @return Object
	 */
	final public int indexOf(final Object label) {
		return getLabels().indexOf(label);
	}

	/**
	 * Note: Returns an index of -1 if the label is missing
	 *
	 * @param labels A List of labels
	 * @return Returns an array of integer indices for the specified labels in order
	 */
	final public int[] indices(final List<?> labels) {
		final int[] res = new int[labels.size()];
		for (int i = 0; i < res.length; ++i)
			res[i] = indexOf(labels.get(i));
		return res;
	}

	/**
	 * Checks the values and covariances to determine whether any are equivalent to
	 * NaN.
	 *
	 * @return boolean true if one value is NaN, false otherwise.
	 */
	final public boolean isNaN() {
		if (getValues().isNaN())
			return true;
		final RealMatrix cov = getCovariances();
		for (int r = 0; r < cov.getRowDimension(); ++r)
			for (int c = 0; c < cov.getColumnDimension(); ++c)
				if (Double.isNaN(getCovariance(r, c)))
					return true;
		return false;
	}

	/**
	 * Returns an UncertainValuesBase object with the labels in the order specified
	 * by the argument. If this is already in this order, this is returned;
	 * otherwise a new {@link UncertainValues} object is created.
	 *
	 * @param <K>  The class implementing the labels
	 * @param list A list of labels specifying the new order
	 * @return {@link UncertainValuesBase}
	 */
	@SuppressWarnings("unchecked")
	public <K extends H> UncertainValuesBase<K> reorder(final List<K> list) {
		if (mLabels.size() == list.size()) {
			// Check if already in correct order...
			boolean eq = true;
			for (int i = 0; (i < list.size()) && eq; ++i)
				if (!mLabels.get(i).equals(list.get(i))) {
					eq = false;
					break;
				}
			if (eq)
				return (UncertainValuesBase<K>) this;
		}
		return new ReorderedUncertainValues<H, K>(list, this);
	}

	/**
	 * Build a new UncertainValues from this one in which the labels have been
	 * sorted into alphabetical order by label.toString().
	 *
	 * @return {@link UncertainValues} A new instance with the same data reordered.
	 */
	final public UncertainValuesBase<H> sort() {
		return sort(new Comparator<H>() {

			@Override
			public int compare(final H o1, final H o2) {
				return o1.toString().compareTo(o2.toString());
			}
		});
	}

	/**
	 * Build a new UncertainValues from this one in which the labels have been
	 * sorted into an order determined by the specified {@link Comparator}.
	 *
	 * @param compare A Comparator
	 * @return {@link UncertainValues} A new instance with the same data reordered.
	 */
	final public UncertainValuesBase<H> sort(final Comparator<H> compare) {
		final List<H> labels = new ArrayList<>(getLabels());
		labels.sort(compare);
		return reorder(labels);
	}

	final public String toCSV() {
		final StringBuffer sb = new StringBuffer(4096);
		sb.append("\"Name\",\"Value\"");
		final RealMatrix cov = getCovariances();
		final List<H> labels = getLabels();
		for (int c = 0; c < cov.getColumnDimension(); ++c) {
			sb.append(",\"");
			sb.append(labels.get(c));
			sb.append("\"");
		}
		sb.append("\n");
		final RealVector vals = getValues();
		for (int r = 0; r < vals.getDimension(); ++r) {
			sb.append("\"");
			sb.append(labels.get(r));
			sb.append("\",");
			sb.append(vals.getEntry(r));
			for (int c = 0; c < cov.getColumnDimension(); ++c) {
				sb.append(",");
				sb.append(cov.getEntry(r, c));
			}
			sb.append("\n");
		}
		return sb.toString();
	}

	@Override
	public String toHTML(final Mode mode) {
		return toHTML(mode, new BasicNumberFormat("0.00E0"));
	}

	/**
	 * Provides a mechanism to convert this {@link UncertainValuesBase} object to
	 * HTML with a little more control over number formating.
	 *
	 * @param mode The HTML output mode
	 * @param nf   A {@link BasicNumberFormat}
	 * @return String
	 */
	final public String toHTML(final Mode mode, //
			final BasicNumberFormat nf) {
		switch (mode) {
		case TERSE: {
			final Table table = new Table();
			for (final H rowLabel : getLabels()) {
				final List<Item> row = new ArrayList<>();
				row.add(Table.th(HTML.toHTML(rowLabel, Mode.TERSE)));
				row.add(Table.tdc(
						nf.formatHTML(getEntry(rowLabel)) + "&pm;" + nf.formatHTML(Math.sqrt(getVariance(rowLabel)))));
				table.addRow(row);
			}
			return table.toHTML(Mode.NORMAL);
		}
		case NORMAL: {
			final Map<H, UncertainValue> tmp = getUncertainValueMap();
			final Table t = new Table();
			final Map<String, H> tagMap = new TreeMap<>();
			for (final H tag : tmp.keySet())
				tagMap.put(HTML.toHTML(tag, Mode.TERSE), tag);
			t.addRow(Table.th("Label"), Table.th("Value"), Table.th("Uncertainty"), Table.th("Fractional"));
			final HalfUpFormat df = new HalfUpFormat("0.0%");
			for (final Map.Entry<String, H> me : tagMap.entrySet()) {
				final UncertainValue uv = tmp.get(me.getValue());
				t.addRow(Table.td(me.getKey()), Table.td(uv.doubleValue()), Table.td(uv.uncertainty()),
						Table.td(df.format(uv.fractionalUncertainty())));
			}
			return t.toHTML(Mode.NORMAL);
		}
		case VERBOSE:
		default: {
			final Table vals = new Table();
			final List<Item> all = new ArrayList<>();
			all.add(Table.td("Name"));
			all.add(Table.tdc("Quantity"));
			all.add(Table.td());
			final List<H> labels = getLabels();
			for (final H colLabel : labels)
				all.add(Table.tdc(HTML.toHTML(colLabel, Mode.TERSE)));
			vals.addRow(all);
			for (int r = 0; r < labels.size(); ++r) {
				final H rowLabel = labels.get(r);
				final List<Item> row = new ArrayList<>();
				row.add(Table.td(HTML.toHTML(rowLabel, Mode.TERSE)));
				row.add(Table.tdc(nf.formatHTML(getEntry(rowLabel))));
				if (r == (labels.size() - 1) / 2)
					row.add(Table.tdc("&nbsp;&nbsp;&plusmn;&nbsp;&nbsp;"));
				else
					row.add(Table.td());
				for (int c = 0; c < mLabels.size(); ++c)
					row.add(Table.tdc(toHTML_Covariance(r, c)));
				vals.addRow(row);
			}
			return vals.toHTML(Mode.NORMAL);
		}
		}
	}

	/**
	 * Convert the covariance at r,c into HTML in a human-friendly manner. Variances
	 * are converted into "(v)^2" and covariances into the correlation coefficient
	 * times sR sC.
	 *
	 * @param r Integer index
	 * @param c Integer index
	 * @return String
	 */
	final public String toHTML_Covariance(final int r, //
			final int c) {
		final double val = getCovariance(r, c);
		if (r == c) {
			final BasicNumberFormat nf = new BasicNumberFormat("0.00E0");
			final String html = "(" + nf.formatHTML(Math.sqrt(Math.abs(val))) + ")<sup>2</sup>";
			if (c >= 0)
				return html;
			else // This is a problem! Highlight it....
				return HTML.fontColor(Transforms.NON_BREAKING_DASH + html, Color.RED);
		} else {
			final double vr = getCovariance(r, r), vc = getCovariance(c, c);
			if ((vr != 0.0) && (vc != 0.0)) {
				final BasicNumberFormat nf = new BasicNumberFormat("0.000");
				final double cc = val / Math.sqrt(vr * vc);
				if (Math.abs(cc) < 1.0e-5)
					return "&nbsp;";
				else {
					final String html = nf.formatHTML(cc) + "&middot;&sigma;<sub>R</sub>&sigma;<sub>C</sub>";
					if ((cc >= -MAX_CORR) || (cc <= MAX_CORR))
						return html;
					else
						return "&nbsp;";
				}
			} else {
				if (val == 0.0)
					return "&nbsp";
				else {
					final BasicNumberFormat nf = new BasicNumberFormat("0.00E0");
					return HTML.fontColor(nf.format(val), Color.RED);
				}
			}
		}
	}

	/**
	 * Converts this {@link UncertainValuesBase} outputs into a simple HTML format.
	 *
	 * @param bnf The {@link BasicNumberFormat}
	 * @return String in HTML
	 */
	final public String toSimpleHTML(final BasicNumberFormat bnf) {
		final Table t0 = new Table();
		{
			final List<Table.Item> row = new ArrayList<>();
			row.add(Table.th("Label"));
			row.add(Table.thc("Value"));
			row.add(Table.thc("&nbsp;"));
			for (int c = 0; c < getDimension(); ++c)
				row.add(Table.thc(HTML.toHTML(getLabel(c), Mode.NORMAL)));
			t0.addRow(row);
		}
		for (int r = 0; r < getDimension(); ++r) {
			final List<Table.Item> row = new ArrayList<>();
			row.add(Table.thc(HTML.toHTML(getLabel(r), Mode.NORMAL)));
			row.add(Table.tdc(bnf.format(getEntry(r))));
			row.add(Table.tdc(r == getDimension() / 2 ? "&#177;" : "&nbsp;"));
			for (int c = 0; c < getDimension(); ++c)
				row.add(Table.tdc(bnf.format(getCovariance(r, c))));
			t0.addRow(row);
		}
		return t0.toHTML(Mode.NORMAL);
	}

	public DataFrame<String, String> toDataFrame() {
		final List<String> keys = getLabels().stream().map(l -> l.toString()).collect(Collectors.toList());
		final DataFrame<String, String> res = DataFrame.of(keys, String.class, columns -> {
			final RealMatrix cov = getCovariances();
			for (int i = 0; i < getDimension(); ++i)
				columns.add(getLabel(i).toString(), Arrays.asList(cov.getColumn(i)));
		});
		return res;
	}

	@Override
	public String toString() {
		final List<H> labels = getLabels();
		final List<H> labelSub = new ArrayList<>();
		for (int i = 0; (i < labels.size()) && (i < 5); ++i)
			labelSub.add(labels.get(i));
		final String lblStr = labelSub.toString();
		return "UVS[" + lblStr.substring(1, lblStr.length() - 1)
				+ (labelSub.size() < labels.size() ? "+" + (labels.size() - labelSub.size()) + " more" : "") + "]";
	}

	final public void validateCovariance() throws ArgumentException {
		final List<H> labels = getLabels();
		final Set<H> dupLabels = new HashSet<>(labels);
		if (dupLabels.size() != labels.size())
			for (final H label : labels)
				if (!dupLabels.contains(label))
					throw new ArgumentException("The label " + label + " is repeated.");
		validateCovariance(labels.size(), getCovariances(),
				Math.max(getValues().getMaxValue(), -getValues().getMinValue()));
	}

	/**
	 * Extracts the labels specified by the indices
	 *
	 * @param indices An integer list of indices
	 * @return List&lt;T&gt;
	 */
	final private List<H> getLabels(final int[] indices) {
		final List<H> res = new ArrayList<>();
		for (int i = 0; i < indices.length; ++i)
			res.add(getLabel(i));
		return Collections.unmodifiableList(res);
	}

	/**
	 * <p>
	 * Computes the differences between the values associated with the specified
	 * class between <code>this</code> and <code>other</code>.
	 * </p>
	 * <p>
	 * Example:
	 * &nbsp;&nbsp;&nbsp;<code>differences(MassFraction.class,k412,k411)</code>
	 * </p>
	 *
	 * @param <T>   A label class extending class H
	 * @param cls   Class&lt;T&gt;
	 * @param other {@link UncertainValuesBase}
	 * @return Map&lt;T, Double&gt;
	 */
	public <T extends H> Map<T, Double> differences(final Class<T> cls, //
			final UncertainValuesBase<H> other) {
		final Map<T, Double> res = new HashMap<>();
		for (final Map.Entry<T, Double> me : getValueMap(cls).entrySet())
			res.put(me.getKey(), me.getValue());
		for (final Map.Entry<T, Double> me : other.getValueMap(cls).entrySet())
			res.put(me.getKey(), res.getOrDefault(me.getKey(), 0.0).doubleValue() - me.getValue().doubleValue());
		return res;
	}

	/**
	 * Returns a {@link RealVector} containing the L-values.
	 *
	 * @return {@link RealVector}
	 */
	abstract public RealVector getValues();

	/**
	 * Returns an L x L matrix containing the covariances.
	 *
	 * @return RealMatrix
	 */
	abstract public RealMatrix getCovariances();
}
