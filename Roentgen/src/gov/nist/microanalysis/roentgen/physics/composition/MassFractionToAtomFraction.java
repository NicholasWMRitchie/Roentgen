package gov.nist.microanalysis.roentgen.physics.composition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import gov.nist.juncertainty.ExplicitMeasurementModel;
import gov.nist.microanalysis.roentgen.ArgumentException;
import gov.nist.microanalysis.roentgen.physics.Element;
import gov.nist.microanalysis.roentgen.physics.composition.MaterialLabel.AtomFraction;
import gov.nist.microanalysis.roentgen.physics.composition.MaterialLabel.AtomicWeight;
import gov.nist.microanalysis.roentgen.physics.composition.MaterialLabel.MassFraction;

/**
 * Converts from MassFraction to AtomFraction
 *
 * @author Nicholas W. M. Ritchie
 *
 */
public class MassFractionToAtomFraction //
		extends ExplicitMeasurementModel<MaterialLabel, AtomFraction> {

	private static List<MaterialLabel> buildInputTags(final Material mat) {
		final List<MaterialLabel> res = new ArrayList<>();
		res.addAll(MaterialLabel.buildMassFractionTags(mat));
		res.addAll(MaterialLabel.buildAtomicWeightTags(mat));
		return res;
	}

	private final Material mMaterial;

	/**
	 * Constructs a AtomicFractionToMassFraction
	 *
	 * @param Composition   comp
	 * @param atomicWeights
	 * @throws ArgumentException
	 */
	public MassFractionToAtomFraction(final Material mat) throws ArgumentException {
		this(mat, Collections.emptySet());
	}

	/**
	 * Constructs a MassFractionToAtomFraction
	 *
	 * @param Composition   comp
	 * @param atomicWeights
	 * @throws ArgumentException
	 */
	public MassFractionToAtomFraction(
			//
			final Material mat, final Collection<Element> atomicWeightElms) throws ArgumentException {
		super(buildInputTags(mat), MaterialLabel.buildAtomFractionTags(mat));
		mMaterial = mat;
	}

	@Override
	public RealVector computeValue(final double[] point) {
		final RealVector vals = buildResult();
		final List<MassFraction> mfTags = MaterialLabel.buildMassFractionTags(mMaterial);
		double denom = 0.0;
		for (final MassFraction mfl : mfTags) {
			final double c1 = getArg(mfl, point);
			final double w1 = getArg(MaterialLabel.buildAtomicWeightTag(mMaterial, mfl.getElement()), point);
			denom += c1 / w1;
		}
		for (final MassFraction mfl1 : mfTags) {
			final Element elm1 = mfl1.getElement();
			final double c1 = getArg(mfl1, point);
			final double w1 = getArg(MaterialLabel.buildAtomicWeightTag(mMaterial, elm1), point);
			setResult(MaterialLabel.buildAtomFractionTag(mMaterial, elm1), vals, (c1 / w1) / denom);
		}
		return vals;
	}

	@Override
	public String toString() {
		return "Mass Fraction to Atom Fraction[" + mMaterial + "]";
	}

	@Override
	public Pair<RealVector, RealMatrix> value(final RealVector point) {
		final RealVector vals = buildResult();
		final RealMatrix jac = buildJacobian();
		final List<MassFraction> mfTags = MaterialLabel.buildMassFractionTags(mMaterial);
		double denom = 0.0;
		for (final MassFraction mfl : mfTags) {
			final double c1 = getArg(mfl, point);
			final double w1 = getArg(MaterialLabel.buildAtomicWeightTag(mMaterial, mfl.getElement()), point);
			denom += c1 / w1;
		}
		for (final MassFraction mfl1 : mfTags) {
			final Element elm1 = mfl1.getElement();
			final AtomFraction afl1 = MaterialLabel.buildAtomFractionTag(mMaterial, elm1);
			final AtomicWeight awl1 = MaterialLabel.buildAtomicWeightTag(mMaterial, elm1);
			final double c1 = getArg(mfl1, point);
			final double w1 = getArg(awl1, point);
			setResult(afl1, vals, (c1 / w1) / denom);
			for (final MassFraction mfl2 : mfTags) {
				final Element elm2 = mfl2.getElement();
				final AtomicWeight awl2 = MaterialLabel.buildAtomicWeightTag(mMaterial, elm2);
				final double w2 = getArg(awl2, point);
				final double c2 = getArg(mfl2, point);
				final double n1 = 1.0 / (w1 * denom), n2 = 1.0 / (w2 * denom);
				if (elm1 == elm2) {
					double ff = n1 * (1.0 - c1 * n1);
					setJacobian(mfl2, afl1, jac, ff);
					setJacobian(awl2, afl1, jac, (-c1 / w1) * ff);

				} else {
					double ff = -c1 * n1 * n2;
					setJacobian(mfl2, afl1, jac, ff);
					setJacobian(awl2, afl1, jac, (-c2 / w2) * ff);
				}
			}
		}
		return Pair.create(vals, jac);
	}
}