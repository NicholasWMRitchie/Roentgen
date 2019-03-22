package gov.nist.microanalysis.roentgen.math.uncertainty;

import java.io.PrintStream;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import com.duckandcover.html.HTML;
import com.duckandcover.html.IToHTML;
import com.duckandcover.html.Table;

import gov.nist.microanalysis.roentgen.utility.FastIndex;
import gov.nist.microanalysis.roentgen.utility.HalfUpFormat;

/**
 * <p>
 * The Jacobian is a matrix consisting of n partial derivatives associated with
 * m functions. This implementation of the Jacobian is designed to work with the
 * CovarianceMatrix class as these two classes are necessary to implement a
 * linear algebra-based implementation
 * </p>
 * <p>
 * Copyright Nicholas W. M. Ritchie 2014-2017
 * </p>
 *
 * @author Nicholas W. M. Ritchie
 * @version $Rev: $
 */
abstract public class LabeledMultivariateJacobianFunction<G, H> //
		implements MultivariateJacobianFunction, IToHTML //
{

	public static PrintStream sDump = null;
	/***
	 * Unique object labels identifying each of the random variable arguments to the
	 * functions
	 */
	private final List<G> mInputLabels;

	/***
	 * Unique object labels identifying each of the functions (in order)
	 */
	private final List<H> mOutputLabels;

	/**
	 * Constructs an instance of the LabeledMultivariateJacobianFunction class for a
	 * N dimensional function of M input values.
	 *
	 * @param inputLabels  A List containing labels for M input variables
	 * @param outputLabels A list containing labels for N output values
	 */
	public LabeledMultivariateJacobianFunction( //
			final List<G> inputLabels, //
			final List<H> outputLabels) //
	{
		validateLabels(inputLabels);
		// assert inputLabels != outputLabels;
		mInputLabels = new FastIndex<>(inputLabels);
		validateLabels(outputLabels);
		mOutputLabels = new FastIndex<>(outputLabels);
	}

	/**
	 * Computes the result {@link RealVector} using the most efficient available
	 * mechanism depending upon whether the instantiated class implements
	 * {@link ILabeledMultivariateFunction} or not. If
	 * {@link ILabeledMultivariateFunction} is available then this is called.
	 * Otherwise value(...) is called and only the {@link RealVector} value portion
	 * is returned.
	 *
	 *
	 * @param inp Evaluation point
	 * @return {@link RealVector} The result values
	 */
	final public RealVector compute(final RealVector inp) {
		if (this instanceof ILabeledMultivariateFunction)
			return ((ILabeledMultivariateFunction<?, ?>) this).optimized(inp);
		else
			return value(inp).getFirst();
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final LabeledMultivariateJacobianFunction<?, ?> other = (LabeledMultivariateJacobianFunction<?, ?>) obj;
		return Objects.equals(mInputLabels, other.mInputLabels) && //
				Objects.equals(mOutputLabels, other.mOutputLabels);
	}

	/**
	 * <p>
	 * A safer version of <code>value(x)</code>.
	 * </p>
	 * <p>
	 * Checks the length of the input argument x, calls <code>value(x)</code> and
	 * then checks the output {@link RealVector} and {@link RealMatrix} to ensure
	 * that they are all the correct dimensions.
	 * </p>
	 *
	 * @param x
	 * @return Pair&lt;{@link RealVector}, {@link RealMatrix}&gt; As from a call to
	 *         <code>value(x)</code>.
	 */
	final public Pair<RealVector, RealMatrix> evaluate(final RealVector x) {
		if (x.getDimension() != getInputDimension())
			throw new DimensionMismatchException(x.getDimension(), getInputDimension());
		final Pair<RealVector, RealMatrix> res = value(x);
		if (res.getFirst().getDimension() != getOutputDimension())
			throw new DimensionMismatchException(res.getFirst().getDimension(), getOutputDimension());
		if (res.getSecond().getRowDimension() != getOutputDimension())
			throw new DimensionMismatchException(res.getSecond().getRowDimension(), getOutputDimension());
		if (res.getSecond().getColumnDimension() != getInputDimension())
			throw new DimensionMismatchException(res.getSecond().getColumnDimension(), getInputDimension());
		return res;
	}

	/**
	 * Extracts from <code>point</code> representing an argument to
	 * <code>nmvj.compute(point)</code> the input RealArray that is suitable as the
	 * argument to <code>this.comptue(...)</code>.
	 *
	 * @param nmvj  The outer {@link LabeledMultivariateJacobianFunction}
	 * @param point A {@link RealVector} of length nmvj.getInputDimension()
	 * @return {@link RealVector} of length this.getInputDimension() containing the
	 *         appropriate input values from point.
	 */
	public RealVector extractArgument(final LabeledMultivariateJacobianFunction<?, ?> nmvj, final RealVector point) {
		assert point.getDimension() == nmvj.getInputDimension();
		final int dim = getInputDimension();
		final RealVector res = new ArrayRealVector(dim);
		final List<? extends G> labels = getInputLabels();
		for (int i = 0; i < dim; ++i) {
			final int idx = nmvj.inputIndex(labels.get(i));
			assert idx != -1 : "Can't find " + labels.get(i) + " in the arguments to " + nmvj.toString();
			res.setEntry(i, point.getEntry(idx));
		}
		return res;
	}

	/**
	 * Number of input random variables expected.
	 *
	 * @return int
	 */
	final public int getInputDimension() {
		return mInputLabels.size();
	}

	final public G getInputLabel(final int idx) {
		return mInputLabels.get(idx);
	}

	/**
	 * Returns an array consisting of the labels associated with the M input random
	 * variables.
	 *
	 * @return List&lt;Object&gt;
	 */
	final public List<G> getInputLabels() {
		return Collections.unmodifiableList(mInputLabels);
	}

	/**
	 * Number of output random variable produced.
	 *
	 * @return int
	 */
	final public int getOutputDimension() {
		return mOutputLabels.size();
	}

	final public H getOutputLabel(final int idx) {
		return mOutputLabels.get(idx);
	}

	/**
	 * Returns an array consisting of the labels associated with the N output
	 * function values.
	 *
	 * @return List&lt;Object&gt;
	 */
	final public List<H> getOutputLabels() {
		return Collections.unmodifiableList(mOutputLabels);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mInputLabels, mOutputLabels);
	}

	/**
	 * A value either with or without uncertainty has been defined for this label.
	 *
	 * @param label
	 * @return true if a value has been defined.
	 */
	final public boolean hasValue(final G label) {
		return (inputIndex(label) != -1);
	}

	/**
	 * Returns the index of the input variable identified by the specified instance
	 * of H.
	 *
	 * @param label
	 * @return int Index or -1 for not found.
	 */
	final public int inputIndex(final Object label) {
		return mInputLabels.indexOf(label);
	}

	/**
	 * Returns the index of the output function identified by the specified instance
	 * of H.
	 *
	 * @param label
	 * @return int Index or -1 for not found.
	 */
	final public int outputIndex(final Object label) {
		return mOutputLabels.indexOf(label);
	}

	@Override
	public String toHTML(final Mode mode) {
		switch (mode) {
		case TERSE: {
			return HTML
					.escape("V[" + getOutputLabels().size() + " values]=F(" + getInputLabels().size() + " arguments)");
		}
		case NORMAL:
			return HTML.escape(toString());
		default:
		case VERBOSE: {
			final Table t = new Table();
			t.addRow(Table.th(HTML.escape(toString()), 2));
			{
				final StringBuffer sb = new StringBuffer();
				for (final Object label : getOutputLabels()) {
					if (sb.length() != 0)
						sb.append("<br/>");
					sb.append(HTML.toHTML(label, Mode.TERSE));
				}
				t.addRow(Table.td("Ouputs"), Table.td(sb.toString()));
			}
			{
				final StringBuffer sb = new StringBuffer();
				for (final Object label : getInputLabels()) {
					if (sb.length() != 0)
						sb.append("<br/>");
					sb.append(HTML.toHTML(label, Mode.TERSE));
				}
				t.addRow(Table.td("Inputs"), Table.td(sb.toString()));
			}
			return t.toHTML(Mode.NORMAL);
		}
		}
	}

	/**
	 * A helper to build an zeroed matrix to contain the result Jacobian in
	 * value(...)
	 *
	 * @return {@link RealMatrix}
	 */
	final protected RealMatrix buildJacobian() {
		return MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());
	}

	/**
	 * A helper to build a zeroed vector to contain the result for value(...)
	 *
	 * @return {@link RealVector}
	 */
	final protected RealVector buildResult() {
		return new ArrayRealVector(getOutputDimension());
	}

	protected void dumpArguments(final RealVector point, final LabeledMultivariateJacobianFunction<?, ?> parent) {
		if (sDump != null) {
			final StringBuffer sb = new StringBuffer();
			final NumberFormat nf = new HalfUpFormat("0.00E0");
			sb.append(toString());
			sb.append("[");
			for (int i = 0; i < getInputDimension(); ++i) {
				if (i != 0)
					sb.append(",");
				final Object lbl = getInputLabel(i);
				sb.append(lbl);
				sb.append("=");
				sb.append(nf.format(point.getEntry(i)));
			}
			sb.append("] in ");
			sb.append(parent);
			sb.append("\n");
			sDump.append(sb);
		}
	}

	/**
	 * Gets the value associated with the specified input variable label from the
	 * {@link RealVector} point which is the argument to the value(...) function.
	 * Typically used to implement the value(...) function.
	 *
	 * @param inLabel A G class label
	 * @param point   The argument to the value(...) function
	 * @return double The value at point.getEntry(inputIndex(inLabel))
	 */
	final protected double getArg(final G inLabel, final RealVector point) {
		return point.getEntry(inputIndex(inLabel));
	}

	/**
	 * Sets the value associated with the specified row (outLabel) and column
	 * (inLabel) in the Jacobian matrix in jacobian to value. Typically used to
	 * implement the value(...) function.
	 *
	 * @param inLabel  G
	 * @param outLabel H
	 * @param jacobian {@link RealMatrix}
	 * @param value    double The value to be assigned to
	 *                 jacobian[outputIndex(outLabel),inputIndex(inLabel)]
	 */
	final protected void setJacobian(final G inlabel, final H outLabel, final RealMatrix jacobian, final double value) {
		jacobian.setEntry(outputIndex(outLabel), inputIndex(inlabel), value);
	}

	/**
	 * Sets the value associated with the specified output variable label to the
	 * result {@link RealVector} point which is to be returned by the value(...)
	 * function. Typically used to implement the value(...) function.
	 *
	 * @param outLabel H A H-class label
	 * @param result   {@link RealVector}
	 * @param value    double The value to be assigned to
	 *                 result[outputIndex(outLabel)]
	 */
	final protected void setResult(final H outLabel, final RealVector result, final double value) {
		result.setEntry(outputIndex(outLabel), value);
	}

	/**
	 * Check labels are only used once.
	 *
	 * @param labels
	 */
	private void validateLabels(final Collection<? extends Object> labels) {
		final Set<? extends Object> set = new HashSet<>(labels);
		for (final Object lbl : labels)
			if (!set.remove(lbl))
				throw new RuntimeException("The label " + lbl + " is duplicated.");
	}
}
