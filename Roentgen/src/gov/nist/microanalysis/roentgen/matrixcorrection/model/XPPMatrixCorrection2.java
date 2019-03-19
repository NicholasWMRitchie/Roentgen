package gov.nist.microanalysis.roentgen.matrixcorrection.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import com.duckandcover.html.IToHTML;
import com.duckandcover.html.Report;
import com.duckandcover.html.Table;

import gov.nist.microanalysis.roentgen.ArgumentException;
import gov.nist.microanalysis.roentgen.EPMALabel;
import gov.nist.microanalysis.roentgen.EPMALabel.MaterialMAC;
import gov.nist.microanalysis.roentgen.math.NullableRealMatrix;
import gov.nist.microanalysis.roentgen.math.uncertainty.CompositeLabeledMultivariateJacobianFunction;
import gov.nist.microanalysis.roentgen.math.uncertainty.ILabeledMultivariateFunction;
import gov.nist.microanalysis.roentgen.math.uncertainty.LabeledMultivariateJacobianFunction;
import gov.nist.microanalysis.roentgen.math.uncertainty.LabeledMultivariateJacobianFunctionBuilder;
import gov.nist.microanalysis.roentgen.math.uncertainty.UncertainValue;
import gov.nist.microanalysis.roentgen.math.uncertainty.UncertainValues;
import gov.nist.microanalysis.roentgen.math.uncertainty.UncertainValuesBase;
import gov.nist.microanalysis.roentgen.math.uncertainty.UncertainValuesCalculator;
import gov.nist.microanalysis.roentgen.matrixcorrection.KRatioLabel;
import gov.nist.microanalysis.roentgen.matrixcorrection.KRatioLabel.Method;
import gov.nist.microanalysis.roentgen.matrixcorrection.Layer;
import gov.nist.microanalysis.roentgen.matrixcorrection.MatrixCorrectionDatum;
import gov.nist.microanalysis.roentgen.matrixcorrection.StandardMatrixCorrectionDatum;
import gov.nist.microanalysis.roentgen.matrixcorrection.UnknownMatrixCorrectionDatum;
import gov.nist.microanalysis.roentgen.physics.AtomicShell;
import gov.nist.microanalysis.roentgen.physics.CharacteristicXRay;
import gov.nist.microanalysis.roentgen.physics.Element;
import gov.nist.microanalysis.roentgen.physics.ElementalMAC;
import gov.nist.microanalysis.roentgen.physics.MaterialMACFunction;
import gov.nist.microanalysis.roentgen.physics.XRay;
import gov.nist.microanalysis.roentgen.physics.XRaySet.CharacteristicXRaySet;
import gov.nist.microanalysis.roentgen.physics.XRaySet.ElementXRaySet;
import gov.nist.microanalysis.roentgen.physics.composition.Composition;
import gov.nist.microanalysis.roentgen.physics.composition.Material;
import gov.nist.microanalysis.roentgen.physics.composition.MaterialLabel;
import gov.nist.microanalysis.roentgen.physics.composition.MaterialLabel.MassFraction;
import gov.nist.microanalysis.roentgen.utility.FastIndex;
import joinery.DataFrame;

/**
 * <p>
 * This class implements the XPP matrix correction algorithm as described in the
 * "Green Book" (Heinrich & Newbury 1991) by Pouchou and Pichoir. It also
 * implements all the Jacobians necessary to implement an uncertainty
 * calculation to propagate uncertainties in the input parameters into output
 * parameters. It breaks the calculation into a series of much simpler steps for
 * which the Jacobian can be readily calculated.
 * <p>
 *
 * <p>
 * Since it is derived from {@link LabeledMultivariateJacobianFunction}, it
 * computes not only the value (ie. the k-ratio) but also sensitivity matrix
 * (Jacobian) that maps uncertainty in the input parameters into uncertainty in
 * the output parameters.
 * <p>
 *
 * <p>
 * The following parameters can have associated uncertainties.
 * <ul>
 * <li>The composition of the standards (user specified)</li>
 * <li>The composition of the unknown (user specified)</li>
 * <li>The beam energy (user specified)</li>
 * <li>The take-off angle (user specified)</li>
 * <li>The mass absorption coefficient (computed using FFAST)</li>
 * <li>The mean ionization coefficient (computed using the equation in
 * PAP1991)</li>
 * <li>Surface roughness (user specified)</li>
 * </ul>
 *
 * <p>
 * This class calculates XPP assuming that all the data associate with the
 * unknown was collected at the same beam energy.
 * <p>
 *
 *
 * @author Nicholas
 */
public class XPPMatrixCorrection2 //
		extends MatrixCorrectionModel2 //
		implements IToHTML {

	private static final String EPS = "&epsilon;";
	private static final String ONE_OVER_S = "<sup>1</sup>/<sub>S</sub>";
	private static final String QLA = "<html>Q<sub>l</sub><sup>a</sup>";
	private static final String ZBARB = "Z<sub>barb</sub>";
	private static final String RBAR = "R<sub>bar</sub>";

	private static final boolean VALIDATE = false;

	private static final double eVtokeV(final double eV) {
		return 0.001 * eV;
	}

	/**
	 * <p>
	 * Do some very basic checks on the input and output indices.
	 * <ul>
	 * <li>Not duplicated..</li>
	 * <li>Not missing</li>
	 * </ul>
	 * </p>
	 *
	 * @param idxs
	 */
	static private void checkIndices(final int... idxs) {
		final int len = idxs.length;
		for (int i = 0; i < len; ++i) {
			assert idxs[i] >= 0;
			for (int j = i + 1; j < len; ++j)
				assert idxs[i] != idxs[j];
		}
	}

	/**
	 * Ensure that the optimized and compute value are identical.
	 *
	 * @param lmjf     {@link ILabeledMultivariateFunction}
	 * @param point    Evaluation point
	 * @param computed Comparison value
	 */
	static private void checkOptimized(final ILabeledMultivariateFunction<EPMALabel, EPMALabel> lmjf,
			final RealVector inp, final RealVector computed) {
		final RealVector optimized = lmjf.optimized(inp);
		for (int i = 0; i < optimized.getDimension(); ++i) {
			final double opt = optimized.getEntry(i);
			final double val = computed.getEntry(i);
			assert Math.abs(opt - val) < 1.0e-6 * Math.abs(val + 1.0e-6);
		}
	}

	/**
	 *
	 *
	 * @param lmjf
	 * @param inp
	 * @param dinp
	 * @param computed
	 */
	static private void checkPartials(//
			final LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> lmjf, //
			final RealVector inp, //
			final RealVector dinp, //
			final RealMatrix computed //
	) {
		final UncertainValuesCalculator.ICalculator calc = new UncertainValuesCalculator.FiniteDifference(dinp);
		final Pair<RealVector, RealMatrix> pr = calc.compute(lmjf, inp);
		final RealMatrix delta = pr.getSecond();
		for (int r = 0; r < delta.getRowDimension(); r++)
			for (int c = 0; c < delta.getColumnDimension(); ++c)
				assert Math.abs(delta.getEntry(r, c) - computed.getEntry(r, c)) < 1.0e-3
						* (Math.abs(computed.getEntry(r, c)) + 1.0e-6) : //
				lmjf.getOutputLabel(r) + " " + lmjf.getInputLabel(c) + " <=> " + delta.getEntry(r, c) + "!="
						+ computed.getEntry(r, c);
	}

	private static class StepMJZBarb // Checked 14-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final Material mMaterial;

		public static List<EPMALabel> buildInputs(//
				final Material comp, //
				final boolean isStandard //
		) {
			final List<EPMALabel> res = new ArrayList<>();
			for (final Element elm : comp.getElementSet()) {
				res.add(MaterialLabel.buildMassFractionTag(comp, elm));
				res.add(MaterialLabel.buildAtomicWeightTag(comp, elm));
				res.add(MatrixCorrectionModel2.meanIonizationLabel(elm));
			}
			return res;
		}

		public static List<EPMALabel> buildOutputs(final Material mat) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(new MatrixCorrectionModel2.MaterialBasedLabel("M", mat));
			res.add(new MatrixCorrectionModel2.MaterialBasedLabel("J", mat));
			res.add(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mat));
			return res;
		}

		public StepMJZBarb( //
				final Material mat, //
				final boolean isStandard //
		) {
			super(buildInputs(mat, isStandard), buildOutputs(mat));
			mMaterial = mat;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final ArrayList<Element> elms = new ArrayList<>(mMaterial.getElementSet());
			final int[] iCi = new int[elms.size()];
			final int[] iJi = new int[elms.size()];
			final int[] iAi = new int[elms.size()];
			final double[] Ci = new double[elms.size()];
			final double[] Ai = new double[elms.size()];
			final double[] Z = new double[elms.size()];
			final double[] Ji = new double[elms.size()];
			for (int i = 0; i < Ci.length; ++i) {
				final Element elm = elms.get(i);
				iCi[i] = inputIndex(MaterialLabel.buildMassFractionTag(mMaterial, elm));
				iJi[i] = inputIndex(MatrixCorrectionModel2.meanIonizationLabel(elm));
				iAi[i] = inputIndex(MaterialLabel.buildAtomicWeightTag(mMaterial, elm));
				Ci[i] = point.getEntry(iCi[i]);
				Ji[i] = point.getEntry(iJi[i]);
				Ai[i] = point.getEntry(iAi[i]);
				Z[i] = elm.getAtomicNumber();
			}

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final Material mat = mMaterial;
			final int oM = outputIndex(new MatrixCorrectionModel2.MaterialBasedLabel("M", mat));
			final int oJ = outputIndex(new MatrixCorrectionModel2.MaterialBasedLabel("J", mat));
			final int oZbarb = outputIndex(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mat));
			checkIndices(oM, oJ, oZbarb);

			// Calculate M & partials
			double M = 0.0;
			for (int i = 0; i < Ci.length; ++i)
				M += Ci[i] * Z[i] / Ai[i];
			rv.setEntry(oM, M); // c1
			for (int i = 0; i < iCi.length; ++i) {
				rm.setEntry(oM, iCi[i], (Z[i] / Ai[i])); // c1
				rm.setEntry(oM, iAi[i], -Ci[i] * (Z[i] / (Ai[i] * Ai[i]))); //
			}

			// Calculate J and partials
			double lnJ = 0.0;
			for (int i = 0; i < Ji.length; ++i)
				lnJ += Math.log(Ji[i]) * Ci[i] * Z[i] / Ai[i];
			lnJ /= M;
			final double J = Math.exp(lnJ); // keV
			rv.setEntry(oJ, J); // C2
			for (int i = 0; i < iCi.length; ++i) {
				final double tmp = (J / M) * (Z[i] / Ai[i]);
				final double logJi = Math.log(Ji[i]);
				rm.setEntry(oJ, iJi[i], tmp * (Ci[i] / Ji[i])); // Ok!
				rm.setEntry(oJ, iCi[i], tmp * (logJi - lnJ)); // Ok!
				rm.setEntry(oJ, iAi[i], tmp * (Ci[i] / Ai[i]) * (lnJ - logJi)); // Ok!
			}
			// Calculate Zbarb and partials
			double Zbt = 0.0;
			for (int i = 0; i < Ci.length; ++i)
				Zbt += Ci[i] * Math.sqrt(Z[i]); // Ok
			rv.setEntry(oZbarb, Zbt * Zbt);
			for (int i = 0; i < elms.size(); ++i)
				rm.setEntry(oZbarb, iCi[i], 2.0 * Math.sqrt(Z[i]) * Zbt); // Ci
			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final List<Element> elms = new ArrayList<>(mMaterial.getElementSet());
			final double[] Ci = new double[elms.size()];
			final double[] ZoA = new double[elms.size()];
			final double[] Z = new double[elms.size()];
			final double[] Ji = new double[elms.size()];
			for (int i = 0; i < Ci.length; ++i) {
				final Element elm = elms.get(i);
				Ci[i] = point.getEntry(inputIndex(MaterialLabel.buildMassFractionTag(mMaterial, elm)));
				Z[i] = elm.getAtomicNumber();
				Ji[i] = point.getEntry(inputIndex(MatrixCorrectionModel2.meanIonizationLabel(elm)));
				final double a = point.getEntry(inputIndex(MaterialLabel.buildAtomicWeightTag(mMaterial, elm)));
				ZoA[i] = Z[i] / a;
			}

			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final int oM = outputIndex(new MatrixCorrectionModel2.MaterialBasedLabel("M", mMaterial));
			final int oJ = outputIndex(new MatrixCorrectionModel2.MaterialBasedLabel("J", mMaterial));
			final int oZbarb = outputIndex(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mMaterial));
			checkIndices(oM, oJ, oZbarb);

			// Calculate M
			double M = 0.0;
			for (int i = 0; i < Ci.length; ++i)
				M += Ci[i] * ZoA[i];
			rv.setEntry(oM, M); // c1
			// Calculate J
			double lnJ = 0.0;
			for (int i = 0; i < Ji.length; ++i)
				lnJ += Math.log(Ji[i]) * Ci[i] * ZoA[i];
			lnJ /= M;
			rv.setEntry(oJ, Math.exp(lnJ)); // C2
			// Calculate Zbarb
			double Zbt = 0.0;
			for (int i = 0; i < Ci.length; ++i)
				Zbt += Ci[i] * Math.sqrt(Z[i]); // Ok
			rv.setEntry(oZbarb, Zbt * Zbt);
			return rv;
		}

		@Override
		public String toString() {
			return "MJZBarb[" + mMaterial.toString() + "]";
		}

	}

	private static class StepQlaE0OneOverS // Checked 14-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final MatrixCorrectionDatum mDatum;
		private final AtomicShell mShell;

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.shellLabel(ONE_OVER_S, datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel(QLA, datum, shell));
			return res;
		}

		public static List<EPMALabel> buildInputs( //
				final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(new MatrixCorrectionModel2.MaterialBasedLabel("M", datum.getMaterial()));
			res.add(new MatrixCorrectionModel2.MaterialBasedLabel("J", datum.getMaterial()));
			res.add(MatrixCorrectionModel2.beamEnergyLabel(datum));
			res.add(new MatrixCorrectionModel2.IonizationExponentLabel(shell));
			return res;
		}

		public StepQlaE0OneOverS(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			super(buildInputs(datum, shell), buildOutputs(datum, shell));
			mDatum = datum;
			mShell = shell;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int tagE0 = inputIndex(MatrixCorrectionModel2.beamEnergyLabel(mDatum));
			final int tagm = inputIndex(new MatrixCorrectionModel2.IonizationExponentLabel(mShell));
			final int iJ = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel("J", mDatum.getMaterial()));
			final int iM = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel("M", mDatum.getMaterial()));
			checkIndices(iJ, iM);

			final double e0 = point.getEntry(tagE0);
			final double m = point.getEntry(tagm);
			final double J = point.getEntry(iJ);
			final double M = point.getEntry(iM);

			final int oOneOverS = outputIndex(MatrixCorrectionModel2.shellLabel(ONE_OVER_S, mDatum, mShell));
			final int oQlaE0 = outputIndex(MatrixCorrectionModel2.shellLabel(QLA, mDatum, mShell));
			checkIndices(oOneOverS, oQlaE0);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final double Ea = eVtokeV(mShell.getEdgeEnergy());

			final double u0 = e0 / Ea;
			final double du0de0 = 1 / Ea;
			assert u0 > 1.0 : e0 + " over " + Ea;
			final double logU0 = Math.log(u0), logU0_2 = logU0 * logU0;

			final double v0 = e0 / J;
			final double dv0dJ = -e0 / (J * J);
			final double v0ou0 = Ea / J; // V0/U0 = (E0/J)/(E0/Ea) = Ea/J
			// QlaE0 and partials
			final double QlaE0 = logU0 / (Math.pow(u0, m) * Math.pow(Ea, 2.0));
			rv.setEntry(oQlaE0, QlaE0); // C1
			rm.setEntry(oQlaE0, tagE0, (1.0 - m * logU0) / (Math.pow(u0, 1.0 + m) * Math.pow(Ea, 3.0))); // C1
			rm.setEntry(oQlaE0, tagm, -QlaE0 * logU0); // Ok

			final double[] D = { 6.6e-6, 1.12e-5 * (1.35 - 0.45 * J * J), 2.2e-6 / J }; // Ok!
			final double[] P = { 0.78, 0.1, 0.25 * J - 0.5 }; // Ok!
			final double[] T = { 1.0 + P[0] - m, 1.0 + P[1] - m, 1.0 + P[2] - m }; // Ok!

			final double[] dDdJ = { 0.0, 2.0 * 1.12e-5 * (-0.45 * J), -2.2e-6 / (J * J) }; // Ok!
			final double[] dPdJ = { 0.0, 0.0, 0.25 }; // Ok!
			final double[] dTdJ = dPdJ; // Ok!
			final double dTdmk = -1.0; // For all 3

			// Compute the sum of the k-terms
			double OoS = 0.0, dOoSdJ = 0.0, dOoSdU0 = 0.0, dOoSdm = 0.0;
			final double kk = J / (Ea * M); // J dependence in sum
			for (int k = 0; k < 3; ++k) {
				final double u0tk = Math.pow(u0, T[k]);
				final double v0ou0_pk = Math.pow(v0ou0, P[k]);
				final double OoSk = kk * D[k] * v0ou0_pk * (u0tk * (T[k] * logU0 - 1.0) + 1.0) / (T[k] * T[k]); // d
				OoS += OoSk;
				dOoSdm += (kk * u0tk * v0ou0_pk * D[k] * logU0_2 - 2.0 * OoSk) * (dTdmk / T[k]); // d
				dOoSdJ += (1.0 / J + dDdJ[k] / D[k] + Math.log(v0ou0) * dPdJ[k] + (P[k] / v0) * dv0dJ
						- 2.0 * dTdJ[k] / T[k]) * OoSk + //
						kk * u0tk * v0ou0_pk * D[k] * logU0_2 * dTdJ[k] / T[k]; // d
				dOoSdU0 += (kk * u0tk * v0ou0_pk * D[k] * logU0) / u0; // d
			}
			// E0/J0 = J/Ea
			rv.setEntry(oOneOverS, OoS); // C2
			rm.setEntry(oOneOverS, iM, (-1.0 / M) * OoS); // C2
			rm.setEntry(oOneOverS, iJ, dOoSdJ); // C2
			rm.setEntry(oOneOverS, tagE0, dOoSdU0 * du0de0); // C2
			rm.setEntry(oOneOverS, tagm, dOoSdm);

			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int tagE0 = inputIndex(MatrixCorrectionModel2.beamEnergyLabel(mDatum));
			final int tagm = inputIndex(new MatrixCorrectionModel2.IonizationExponentLabel(mShell));
			final int iJ = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel("J", mDatum.getMaterial()));
			final int iM = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel("M", mDatum.getMaterial()));
			checkIndices(iJ, iM);

			final double e0 = point.getEntry(tagE0);
			final double m = point.getEntry(tagm);
			final double J = point.getEntry(iJ);
			final double M = point.getEntry(iM);

			final int oOneOverS = outputIndex(MatrixCorrectionModel2.shellLabel(ONE_OVER_S, mDatum, mShell));
			final int oQlaE0 = outputIndex(MatrixCorrectionModel2.shellLabel(QLA, mDatum, mShell));
			checkIndices(oOneOverS, oQlaE0);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final double Ea = eVtokeV(mShell.getEdgeEnergy());
			final double u0 = e0 / Ea;
			assert u0 > 1.0 : e0 + " over " + Ea + " for " + mDatum + " - " + mShell;
			final double logU0 = Math.log(u0);
			// QlaE0 and partials
			final double QlaE0 = logU0 / (Math.pow(u0, m) * Math.pow(Ea, 2.0));
			rv.setEntry(oQlaE0, QlaE0); // C1
			rm.setEntry(oQlaE0, tagE0, Math.pow(u0, -1.0 - m) * (1.0 - m * logU0) / Math.pow(Ea, 3.0)); // C1
			rm.setEntry(oQlaE0, tagm, -QlaE0 * logU0);

			final double[] D = { 6.6e-6, 1.12e-5 * (1.35 - 0.45 * J * J), 2.2e-6 / J };
			final double[] P = { 0.78, 0.1, 0.25 * J - 0.5 };
			final double[] T = { 1.0 + P[0] - m, 1.0 + P[1] - m, 1.0 + P[2] - m };

			final double v0ou0 = Ea / J; // c1

			double OoS = 0.0;
			final double kk = J / (Ea * M);
			for (int k = 0; k < 3; ++k) {
				final double u0tk = Math.pow(u0, T[k]);
				final double v0ou0_pk = Math.pow(v0ou0, P[k]);
				OoS += kk * D[k] * v0ou0_pk * (u0tk * (T[k] * logU0 - 1.0) + 1.0) / (T[k] * T[k]); // d
			}
			rv.setEntry(oOneOverS, OoS); // C2
			return rv;
		}

		@Override
		public String toString() {
			return "StepQlaE0OneOverS[" + mDatum + "," + mShell + "]";
		}
	}

	private static class StepRphi0 // Checked 15-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final MatrixCorrectionDatum mDatum;
		private final AtomicShell mShell;

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.shellLabel("R", datum, shell));
			res.add(MatrixCorrectionModel2.phi0Label(datum, shell));
			return res;
		}

		public static List<EPMALabel> buildInputs(final MatrixCorrectionDatum datum) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, datum.getMaterial()));
			res.add(MatrixCorrectionModel2.beamEnergyLabel(datum));
			return res;
		}

		public StepRphi0(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			super(buildInputs(datum), buildOutputs(datum, shell));
			mDatum = datum;
			mShell = shell;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int iZbarb = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mDatum.getMaterial()));
			final int tagE0 = inputIndex(MatrixCorrectionModel2.beamEnergyLabel(mDatum));
			checkIndices(iZbarb);

			final double Ea = eVtokeV(mShell.getEdgeEnergy());
			final double Zbarb = point.getEntry(iZbarb);
			final double e0 = point.getEntry(tagE0);

			final double u0 = e0 / Ea;
			final double du0de0 = 1.0 / Ea;

			final double etabar = 1.75e-3 * Zbarb + 0.37 * (1.0 - Math.exp(-0.015 * Math.pow(Zbarb, 1.3))); // Ok!
			final double detabardZbarb = 1.75e-3 //
					+ 7.215e-3 * Math.pow(Zbarb, 0.3) * Math.exp(-0.015 * Math.pow(Zbarb, 1.3)); // Ok!

			final double Wbar = 0.595 + etabar / 3.7 + Math.pow(etabar, 4.55); // Ok!
			final double dWbardeta = 1.0 / 3.7 + 4.55 * Math.pow(etabar, 3.55); // Ok!
			final double dWbardZbarb = dWbardeta * detabardZbarb; // Ok!

			final double q = (2.0 * Wbar - 1.0) / (1.0 - Wbar); // Ok!
			final double dqdWbar = Math.pow(Wbar - 1.0, -2.0); // Ok!
			final double dqdZbarb = dqdWbar * dWbardZbarb; // Ok

			final double Ju0 = 1.0 + u0 * (Math.log(u0) - 1.0); // Ok!
			final double dJu0du0 = Math.log(u0); // Ok!

			final double opq = 1.0 + q;
			final double Gu0 = (u0 * opq - (2.0 + q) + Math.pow(u0, -1.0 - q)) / //
					(opq * (2.0 + q) * Ju0); // Ok!
			// assert Math.abs(Gu0 - ((u0 - 1.0 - (1.0 - Math.pow(u0, -1.0 - q)) / opq) /
			// ((2.0 + q) * Ju0))) < Math.abs(1.0e-6 * Gu0);
			final double dGu0du0 = (1.0 - Math.pow(u0, -2.0 - q)) / ((2.0 + q) * Ju0) //
					- ((dJu0du0 / Ju0) * Gu0); // Ok!
			final double dGu0de0 = dGu0du0 * du0de0; // Ok!
			final double dGu0dq = (((Math.pow(u0, opq) - 1.0) - opq * Math.log(u0)) //
					/ (Ju0 * Math.pow(u0, opq) * Math.pow(opq, 2.0)) - Gu0) //
					/ (2.0 + q); // Ok!
			final double dGu0dZbarb = dGu0dq * dqdZbarb;

			final double R = 1.0 - etabar * Wbar * (1.0 - Gu0); // Ok!
			final double dRde0 = etabar * Wbar * dGu0de0; // Ok!
			final double dRdZbarb = etabar * (Wbar * dGu0dZbarb - (1.0 - Gu0) * dWbardZbarb) - //
					(1.0 - Gu0) * Wbar * detabardZbarb; // Ok!

			final double u0_mr = Math.pow(u0, 2.3 * etabar - 2.0);
			final double phi0 = 1.0 + 3.3 * Math.pow(etabar, 1.2) * (1.0 - u0_mr); // Ok!
			final double dphi0detabar = Math.pow(etabar, 0.2) //
					* (3.96 * (1.0 - u0_mr) - 7.59 * etabar * u0_mr * Math.log(u0)); // Ok!
			final double dphi0dZbarb = dphi0detabar * detabardZbarb; // Ok!
			final double dphi0du0 = -3.3 * Math.pow(etabar, 1.2) * (2.3 * etabar - 2.0) * u0_mr / u0; // Ok!
			final double dphi0de0 = dphi0du0 * du0de0; // Ok!

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final int oR = outputIndex(MatrixCorrectionModel2.shellLabel("R", mDatum, mShell));
			final int oPhi0 = outputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			checkIndices(oR, oPhi0);

			rv.setEntry(oR, R);
			rm.setEntry(oR, iZbarb, dRdZbarb);
			rm.setEntry(oR, tagE0, dRde0);

			rv.setEntry(oPhi0, phi0);
			rm.setEntry(oPhi0, iZbarb, dphi0dZbarb);
			rm.setEntry(oPhi0, tagE0, dphi0de0);

			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int iZbarb = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mDatum.getMaterial()));
			checkIndices(iZbarb);

			final double Ea = eVtokeV(mShell.getEdgeEnergy());
			final double e0 = point.getEntry(inputIndex(MatrixCorrectionModel2.beamEnergyLabel(mDatum)));
			final double u0 = e0 / Ea;
			final double Zbarb = point.getEntry(iZbarb);

			final double etabar = 1.75e-3 * Zbarb + 0.37 * (1.0 - Math.exp(-0.015 * Math.pow(Zbarb, 1.3))); // Ok!
			final double Wbar = 0.595 + etabar / 3.7 + Math.pow(etabar, 4.55); // Ok!
			final double q = (2.0 * Wbar - 1.0) / (1.0 - Wbar); // Ok!
			final double Ju0 = 1.0 + u0 * (Math.log(u0) - 1.0); // Ok!
			final double Gu0 = (u0 * (1.0 + q) - (2.0 + q) + Math.pow(u0, -1.0 - q)) / ((1.0 + q) * (2.0 + q) * Ju0);
			final double R = 1.0 - etabar * Wbar * (1.0 - Gu0);

			final double u0_mr = Math.pow(u0, 2.3 * etabar - 2.0);
			final double phi0 = 1.0 + 3.3 * Math.pow(etabar, 1.2) * (1.0 - u0_mr); // Ok!

			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final int oR = outputIndex(MatrixCorrectionModel2.shellLabel("R", mDatum, mShell));
			final int oPhi0 = outputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			checkIndices(oR, oPhi0);

			rv.setEntry(oR, R);

			rv.setEntry(oPhi0, phi0);
			return rv;
		}

		@Override
		public String toString() {
			return "StepRPhi0[" + mDatum + "," + mShell + "]";
		}

	}

	private static class StepFRbar // Checked 15-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final MatrixCorrectionDatum mDatum;
		private final AtomicShell mShell;

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.shellLabel(RBAR, datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("F", datum, shell));
			return res;
		}

		public static List<EPMALabel> buildInputs(//
				final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, datum.getMaterial()));
			res.add(MatrixCorrectionModel2.beamEnergyLabel(datum));
			res.add(MatrixCorrectionModel2.shellLabel(ONE_OVER_S, datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel(QLA, datum, shell));
			res.add(MatrixCorrectionModel2.phi0Label(datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("R", datum, shell));
			return res;
		}

		public StepFRbar(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			super(buildInputs(datum, shell), buildOutputs(datum, shell));
			mDatum = datum;
			mShell = shell;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int iE0 = inputIndex(MatrixCorrectionModel2.beamEnergyLabel(mDatum));
			final int iZbarb = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mDatum.getMaterial()));
			final int iOneOverS = inputIndex(MatrixCorrectionModel2.shellLabel(ONE_OVER_S, mDatum, mShell));
			final int iQlaE0 = inputIndex(MatrixCorrectionModel2.shellLabel(QLA, mDatum, mShell));
			final int iPhi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			final int iR = inputIndex(MatrixCorrectionModel2.shellLabel("R", mDatum, mShell));
			checkIndices(iZbarb, iOneOverS, iQlaE0, iPhi0, iR);

			final double Ea = eVtokeV(mShell.getEdgeEnergy());
			final double u0 = point.getEntry(iE0) / Ea;
			final double Zbarb = point.getEntry(iZbarb);
			final double oneOverS = point.getEntry(iOneOverS);
			final double QlaE0 = point.getEntry(iQlaE0);
			final double phi0 = point.getEntry(iPhi0);
			final double R = point.getEntry(iR);

			final double logZbarb = Math.log(Zbarb);
			final double X = 1.0 + 1.3 * logZbarb; // Ok
			final double Y = 0.2 + 0.005 * Zbarb; // Ok
			final double dXdZbarb = 1.3 / Zbarb, dYdZbarb = 0.005; // Ok
			final double pu42 = Math.pow(u0, 0.42);
			final double log1pY = Math.log(1.0 + Y);
			final double arg = 1.0 + Y * (1.0 - 1.0 / pu42);
			final double logArg = Math.log(arg);
			final double FoRbar = 1.0 + X * logArg / log1pY; // Corrected 15-Jan-2019
			final double dFoRbardX = logArg / log1pY; // Ok
			final double dFoRbardY = X * ((pu42 - 1.0) * log1pY / (pu42 + Y * (pu42 - 1.0)) - logArg / (1.0 + Y)) //
					/ Math.pow(log1pY, 2.0); // Ok
			final double dFoRbardZbarb = dFoRbardX * dXdZbarb + dFoRbardY * dYdZbarb; // Ok
			final double dFoRbardu0 = (0.42 * X * Y) / (u0 * pu42 * log1pY * arg); // Ok

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final int oRbar = outputIndex(MatrixCorrectionModel2.shellLabel(RBAR, mDatum, mShell));
			final int oF = outputIndex(MatrixCorrectionModel2.shellLabel("F", mDatum, mShell));
			checkIndices(oRbar, oF);

			// F and partials
			final double F = R * oneOverS / QlaE0; // C1
			rv.setEntry(oF, F);
			final double dFdR = oneOverS / QlaE0; // C1
			rm.setEntry(oF, iR, dFdR);
			final double dFdOneOverS = R / QlaE0; // C1
			rm.setEntry(oF, iOneOverS, dFdOneOverS);
			final double dFdQlaE0 = -1.0 * R * oneOverS / Math.pow(QlaE0, 2.0); // C1
			rm.setEntry(oF, iQlaE0, dFdQlaE0);

			// Rbar and partials
			double Rbar = Double.NaN;
			double dRbardF = Double.NaN;
			if (FoRbar >= phi0) {
				Rbar = F / FoRbar; // C1
				dRbardF = 1.0 / FoRbar;
				final double dU0dE0 = 1.0 / Ea;
				final double dRbardFoRbar = -F / Math.pow(FoRbar, 2); // Ok
				rm.setEntry(oRbar, iE0, dRbardFoRbar * dFoRbardu0 * dU0dE0); // Ok
				rm.setEntry(oRbar, iZbarb, dRbardFoRbar * dFoRbardZbarb); // Ok
			} else {
				Rbar = F / phi0;
				dRbardF = 1.0 / phi0;
				rm.setEntry(oRbar, iPhi0, -F / Math.pow(phi0, 2));
				// dRbardE0 and dRbardZbarb = 0!
			}
			rv.setEntry(oRbar, Rbar);
			rm.setEntry(oRbar, iR, dRbardF * dFdR);
			rm.setEntry(oRbar, iOneOverS, dRbardF * dFdOneOverS);
			rm.setEntry(oRbar, iQlaE0, dRbardF * dFdQlaE0);
			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int iZbarb = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mDatum.getMaterial()));
			final int iOneOverS = inputIndex(MatrixCorrectionModel2.shellLabel(ONE_OVER_S, mDatum, mShell));
			final int iQlaE0 = inputIndex(MatrixCorrectionModel2.shellLabel(QLA, mDatum, mShell));
			final int iPhi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			final int iR = inputIndex(MatrixCorrectionModel2.shellLabel("R", mDatum, mShell));
			checkIndices(iZbarb, iOneOverS, iQlaE0, iPhi0, iR);

			final double Ea = eVtokeV(mShell.getEdgeEnergy());
			final double u0 = point.getEntry(inputIndex(MatrixCorrectionModel2.beamEnergyLabel(mDatum))) / Ea;
			final double Zbarb = point.getEntry(iZbarb);
			final double oneOverS = point.getEntry(iOneOverS);
			final double QlaE0 = point.getEntry(iQlaE0);
			final double phi0 = point.getEntry(iPhi0);
			final double R = point.getEntry(iR);

			final double logZbarb = Math.log(Zbarb);
			final double X = 1.0 + 1.3 * logZbarb;
			final double Y = 0.2 + 0.005 * Zbarb;
			final double pu42 = Math.pow(u0, 0.42);
			final double FoRbar = 1.0 + (X * Math.log(1.0 + Y * (1.0 - 1.0 / pu42)) / Math.log(1.0 + Y));

			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final int oRbar = outputIndex(MatrixCorrectionModel2.shellLabel(RBAR, mDatum, mShell));
			final int oF = outputIndex(MatrixCorrectionModel2.shellLabel("F", mDatum, mShell));
			checkIndices(oRbar, oF);

			// F and partials
			final double F = R * oneOverS / QlaE0; // C1
			rv.setEntry(oF, F);

			// Rbar and partials
			double Rbar = Double.NaN;
			if (FoRbar >= phi0)
				Rbar = F / FoRbar; // C1
			else
				Rbar = F / phi0;
			rv.setEntry(oRbar, Rbar);
			return rv;
		}

		@Override
		public String toString() {
			return "StepFRbar[" + mDatum + "," + mShell + "]";
		}

	}

	private static class StepPb // Checked 16-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final MatrixCorrectionDatum mDatum;
		private final AtomicShell mShell;

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.shellLabel("P", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("b", datum, shell));
			return res;
		}

		public static List<EPMALabel> buildInputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.shellLabel(RBAR, datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("F", datum, shell));
			res.add(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, datum.getMaterial()));
			res.add(MatrixCorrectionModel2.beamEnergyLabel(datum));
			res.add(MatrixCorrectionModel2.phi0Label(datum, shell));
			return res;
		}

		StepPb(final MatrixCorrectionDatum comp, final AtomicShell shell) {
			super(buildInputs(comp, shell), buildOutputs(comp, shell));
			mDatum = comp;
			mShell = shell;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int iZbarb = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mDatum.getMaterial()));
			final int iE0 = inputIndex(MatrixCorrectionModel2.beamEnergyLabel(mDatum));
			final int iRbar = inputIndex(MatrixCorrectionModel2.shellLabel(RBAR, mDatum, mShell));
			final int iF = inputIndex(MatrixCorrectionModel2.shellLabel("F", mDatum, mShell));
			final int iphi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			checkIndices(iZbarb, iRbar, iF, iphi0);

			final double Zbarb = point.getEntry(iZbarb);
			final double e0 = point.getEntry(iE0);
			final double Ea = eVtokeV(mShell.getEdgeEnergy());
			final double Rbar = point.getEntry(iRbar);
			final double F = point.getEntry(iF);
			final double phi0 = point.getEntry(iphi0);

			final double u0 = e0 / Ea;
			final double du0de0 = 1.0 / Ea;
			final double k11_375 = 11.0 / 375.0;
			// P and partials
			final double kg = Math.exp((-1.0 / 15.0) * Zbarb * (u0 - 1.0));
			final double log4Zbarb = Math.log(4.0 * Zbarb);
			final double g = 0.22 * log4Zbarb * (1.0 - 2.0 * kg); // Ok
			final double dgdzbarb = g / (Zbarb * log4Zbarb) + k11_375 * kg * (u0 - 1.0) * log4Zbarb; // Ok
			final double dgdu0 = k11_375 * kg * Zbarb * log4Zbarb;

			final double h = 1.0 - 10.0 * (1.0 - 1.0 / (1.0 + 0.1 * u0)) / Math.pow(Zbarb, 2.0); // Ok
			final double dhdzbarb = 20.0 * (1.0 - 1.0 / (1.0 + 0.1 * u0)) / Math.pow(Zbarb, 3.0); // Ok
			final double dhdu0 = -Math.pow(((1 + u0 / 10) * Zbarb), -2.0);

			final double b = Math.sqrt(2.0) * (1.0 + Math.sqrt(1.0 - Rbar * phi0 / F)) / Rbar;
			final double dbdrbar = -b / Rbar - phi0 / (F * Rbar * Math.sqrt(2.0 * (1.0 - phi0 * Rbar / F)));
			final double dbdphi0 = -1.0 / (F * Math.sqrt(2.0 * (1.0 - phi0 * Rbar / F)));
			final double dbdF = phi0 / (F * F * Math.sqrt(2 * (1.0 - phi0 * Rbar / F)));

			// Two different ways to compute gh4
			final double gh4_1 = g * Math.pow(h, 4.0);
			final double gh4_2 = 0.9 * b * Math.pow(Rbar, 2.0) * (b - 2.0 * phi0 / F);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final int oP = outputIndex(MatrixCorrectionModel2.shellLabel("P", mDatum, mShell));
			final int ob = outputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mShell));
			checkIndices(oP, ob);

			if (gh4_1 < gh4_2) {
				// Use gh4_1. Depends on Zbarb, u0, F, Rbar
				final double P = gh4_1 * F / Math.pow(Rbar, 2.0);
				final double dPdzbarb = P * ((1.0 / g) * dgdzbarb + (4.0 / h) * dhdzbarb);
				final double dPdzu0 = P * ((1 / g) * dgdu0 + (4.0 / h) * dhdu0);

				rv.setEntry(oP, P);
				rm.setEntry(oP, iZbarb, dPdzbarb);
				rm.setEntry(oP, iE0, dPdzu0 * du0de0);
				rm.setEntry(oP, iF, P / F);
				rm.setEntry(oP, iRbar, -2.0 * P / Rbar);
			} else {
				// Depends on F, Rbar, b, phi0
				final double P = 0.9 * b * (b * F - 2.0 * phi0);
				final double dPdb = 1.8 * (b * F - phi0);
				final double dPdF = dPdb * dbdF + 0.9 * b * b;
				final double dPdphi0 = -1.8 * b;
				rv.setEntry(oP, P);
				rm.setEntry(oP, iphi0, dPdphi0 + dPdb * dbdphi0);
				rm.setEntry(oP, iF, dPdF);
				rm.setEntry(oP, iRbar, dPdb * dbdrbar);
				rm.setEntry(oP, iRbar, 0.0);
			}

			final double k3 = Math.sqrt(1. - (phi0 * Rbar) / F);

			rv.setEntry(ob, b);
			rm.setEntry(ob, iRbar,
					(-1. * (-0.5 * phi0 * Rbar + F * (1. + k3)) * Math.sqrt(2.0)) / (F * Math.pow(Rbar, 2.0) * k3));
			rm.setEntry(ob, iphi0, -Math.sqrt(2.0) / (2. * F * k3));
			rm.setEntry(ob, iF, (phi0 * Math.sqrt(2.0)) / (2. * Math.pow(F, 2) * k3));

			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int iZbarb = inputIndex(new MatrixCorrectionModel2.MaterialBasedLabel(ZBARB, mDatum.getMaterial()));
			final int iRbar = inputIndex(MatrixCorrectionModel2.shellLabel(RBAR, mDatum, mShell));
			final int iF = inputIndex(MatrixCorrectionModel2.shellLabel("F", mDatum, mShell));
			final int iphi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			checkIndices(iZbarb, iRbar, iF, iphi0);

			final double Zbarb = point.getEntry(iZbarb);
			final double e0 = point.getEntry(inputIndex(MatrixCorrectionModel2.beamEnergyLabel(mDatum)));
			final double Ea = eVtokeV(mShell.getEdgeEnergy());
			final double u0 = e0 / Ea;
			final double Rbar = point.getEntry(iRbar);
			final double F = point.getEntry(iF);
			final double phi0 = point.getEntry(iphi0);

			// P and partials
			final double expArg = Math.exp((-1.0 / 15.0) * Zbarb * (u0 - 1.0));
			final double g = 0.22 * Math.log(4.0 * Zbarb) * (1.0 - 2.0 * expArg);
			final double h = 1.0 - 10.0 * (1.0 - 1.0 / (1.0 + 0.1 * u0)) / Math.pow(Zbarb, 2.0);
			final double b = Math.sqrt(2.0) * (1.0 + Math.sqrt(1.0 - Rbar * phi0 / F)) / Rbar;
			final double Rbar2 = Math.pow(Rbar, 2.0);

			// Two different ways to compute gh4
			final double gh4_1 = g * Math.pow(h, 4.0);
			final double gh4_2 = 0.9 * b * Rbar2 * (b - 2.0 * phi0 / F);

			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final int oP = outputIndex(MatrixCorrectionModel2.shellLabel("P", mDatum, mShell));
			final int ob = outputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mShell));
			checkIndices(oP, ob);

			if (gh4_1 < gh4_2) {
				// Depends on Zbarb, u0, F, Rbar
				final double P = gh4_1 * F / Rbar2;
				rv.setEntry(oP, P);
			} else {
				// Depends on F, Rbar, b, phi0
				final double P = gh4_2 * F / Rbar2;
				rv.setEntry(oP, P);
			}
			rv.setEntry(ob, b);

			return rv;
		}

		@Override
		public String toString() {
			return "StepP[" + mDatum + "," + mShell + "]";
		}

	}

	private static class Stepa // Checked 16-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final MatrixCorrectionDatum mDatum;
		private final AtomicShell mShell;

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			return Collections.singletonList(MatrixCorrectionModel2.shellLabel("a", datum, shell));
		}

		public static List<EPMALabel> buildInputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.phi0Label(datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("P", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel(RBAR, datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("F", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("b", datum, shell));
			return res;
		}

		public Stepa(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			super(buildInputs(datum, shell), buildOutputs(datum, shell));
			mDatum = datum;
			mShell = shell;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int iphi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			final int iF = inputIndex(MatrixCorrectionModel2.shellLabel("F", mDatum, mShell));
			final int iP = inputIndex(MatrixCorrectionModel2.shellLabel("P", mDatum, mShell));
			final int iRbar = inputIndex(MatrixCorrectionModel2.shellLabel(RBAR, mDatum, mShell));
			final int ib = inputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mShell));
			checkIndices(iphi0, iF, iP, iRbar, ib);

			final double phi0 = point.getEntry(iphi0);
			final double P = point.getEntry(iP);
			final double Rbar = point.getEntry(iRbar);
			final double F = point.getEntry(iF);
			final double b = point.getEntry(ib);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final int oa = outputIndex(MatrixCorrectionModel2.shellLabel("a", mDatum, mShell));
			checkIndices(oa);

			final double b2 = Math.pow(b, 2.0);
			final double den = Math.pow(phi0 + b2 * F * Rbar - 2.0 * b * F, 2.0);
			final double a = (P + b * (2.0 * phi0 - b * F)) / (b * F * (2.0 - b * Rbar) - phi0); // Ok
			final double dadphi0 = (3.0 * b2 * F + P - 2.0 * b2 * b * F * Rbar) / den; // Ok
			final double dadP = 1.0 / (b * F * (2.0 - b * Rbar) - phi0); // Ok
			final double dadb = -2.0 * (F * P + phi0 * phi0 - b * F * (phi0 + P * Rbar) + b2 * F * (F - phi0 * Rbar))
					/ den; // Ok
			final double dadF = b * (P * (-2.0 + b * Rbar) + b * phi0 * (2.0 * b * Rbar - 3.0)) / den; // Ok
			final double dadRbar = (b2 * F * (P + 2.0 * b * phi0 - b2 * F)) / den; // Ok

			rv.setEntry(oa, a); // C1
			rm.setEntry(oa, iP, dadP); // C1
			rm.setEntry(oa, ib, dadb); // C1
			rm.setEntry(oa, iphi0, dadphi0); // C1
			rm.setEntry(oa, iF, dadF); // C1
			rm.setEntry(oa, iRbar, dadRbar); // C1

			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int iphi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			final int iF = inputIndex(MatrixCorrectionModel2.shellLabel("F", mDatum, mShell));
			final int iP = inputIndex(MatrixCorrectionModel2.shellLabel("P", mDatum, mShell));
			final int iRbar = inputIndex(MatrixCorrectionModel2.shellLabel(RBAR, mDatum, mShell));
			final int ib = inputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mShell));
			checkIndices(iphi0, iF, iP, iRbar, ib);

			final double phi0 = point.getEntry(iphi0);
			final double P = point.getEntry(iP);
			final double Rbar = point.getEntry(iRbar);
			final double F = point.getEntry(iF);
			final double b = point.getEntry(ib);

			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final int oa = outputIndex(MatrixCorrectionModel2.shellLabel("a", mDatum, mShell));
			checkIndices(oa);

			final double a = (P + b * (2.0 * phi0 - b * F)) / (b * F * (2.0 - b * Rbar) - phi0); // C1

			rv.setEntry(oa, a); // C1
			return rv;
		}

		@Override
		public String toString() {
			return "Stepa[" + mDatum + "," + mShell + "]";
		}
	};

	static private class StepEps // Checked 16-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private static final double MIN_EPS = 1.0e-6;

		private final MatrixCorrectionDatum mDatum;
		private final AtomicShell mShell;

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			return Collections.singletonList(MatrixCorrectionModel2.shellLabel(EPS, datum, shell));
		}

		public static List<EPMALabel> buildInputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.shellLabel("a", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("b", datum, shell));
			return res;
		}

		public StepEps(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			super(buildInputs(datum, shell), buildOutputs(datum, shell));
			mDatum = datum;
			mShell = shell;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int ia = inputIndex(MatrixCorrectionModel2.shellLabel("a", mDatum, mShell));
			final int ib = inputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mShell));
			checkIndices(ia, ib);

			final double a = point.getEntry(ia);
			final double b = point.getEntry(ib);

			final int oeps = outputIndex(MatrixCorrectionModel2.shellLabel(EPS, mDatum, mShell));
			checkIndices(oeps);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final double eps = (a - b) / b;
			if (Math.abs(eps) >= MIN_EPS) {
				rv.setEntry(oeps, eps); // C1
				rm.setEntry(oeps, ia, 1.0 / b); // C1
				rm.setEntry(oeps, ib, -a / (b * b)); // C1
			} else {
				rv.setEntry(oeps, MIN_EPS); // C1
				// rm.setEntry(oeps, ia, 0.0); // C1
				// rm.setEntry(oeps, ib, 0.0); // C1
			}
			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int ia = inputIndex(MatrixCorrectionModel2.shellLabel("a", mDatum, mShell));
			final int ib = inputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mShell));
			checkIndices(ia, ib);

			final double a = point.getEntry(ia);
			final double b = point.getEntry(ib);

			final int oeps = outputIndex(MatrixCorrectionModel2.shellLabel(EPS, mDatum, mShell));
			checkIndices(oeps);

			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final double eps = (a - b) / b;
			if (Math.abs(eps) >= MIN_EPS) {
				rv.setEntry(oeps, eps); // C1
			} else {
				rv.setEntry(oeps, MIN_EPS); // C1
			}
			return rv;
		}

		@Override
		public String toString() {
			return "StepEPS[" + mDatum + "," + mShell + "]";
		}
	}

	private static class StepAB // Checked 17-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final MatrixCorrectionDatum mDatum;
		private final AtomicShell mShell;

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.shellLabel("A", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("B", datum, shell));
			return res;
		}

		public static List<EPMALabel> buildInputs(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.phi0Label(datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("F", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("P", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("b", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel(EPS, datum, shell));
			return res;
		}

		public StepAB(final MatrixCorrectionDatum datum, final AtomicShell shell) {
			super(buildInputs(datum, shell), buildOutputs(datum, shell));
			mDatum = datum;
			mShell = shell;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int iphi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			final int iF = inputIndex(MatrixCorrectionModel2.shellLabel("F", mDatum, mShell));
			final int iP = inputIndex(MatrixCorrectionModel2.shellLabel("P", mDatum, mShell));
			final int ib = inputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mShell));
			final int ieps = inputIndex(MatrixCorrectionModel2.shellLabel(EPS, mDatum, mShell));
			checkIndices(iphi0, iF, iP, ib, ieps);

			final double phi0 = point.getEntry(iphi0);
			final double P = point.getEntry(iP);
			final double F = point.getEntry(iF);
			final double b = point.getEntry(ib);
			final double eps = point.getEntry(ieps);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());
			final int oA = outputIndex(MatrixCorrectionModel2.shellLabel("A", mDatum, mShell));
			final int oB = outputIndex(MatrixCorrectionModel2.shellLabel("B", mDatum, mShell));
			checkIndices(oA, oB);
			// Page 62
			final double B = (b * b * F * (1.0 + eps) - P - phi0 * b * (2.0 + eps)) / eps; // Ok
			final double dBdphi0 = -b * (2.0 + eps) / eps; // Ok
			final double dBdP = -1.0 / eps; // Ok
			final double dBdF = (b * b * (1.0 + eps)) / eps; // Ok
			final double dBdb = (2.0 * b * F * (1.0 + eps) - phi0 * (2.0 + eps)) / eps; // Ok
			final double dBdeps = (P + 2.0 * b * phi0 - b * b * F) / (eps * eps); // Ok

			final double kk = (1.0 + eps) / eps;
			final double A = (B / b + phi0 - b * F) * kk; // Ok
			final double dAdb = (dBdb / b - B / (b * b) - F) * kk; // Ok
			final double dAdphi0 = ((b + dBdphi0) / b) * kk; // Ok
			final double dAdF = ((dBdF - b * b) / b) * kk; // Ok
			final double dAdeps = -A / (eps * (1.0 + eps)) + (dBdeps / b) * kk; // Ok
			final double dAdP = kk * dBdP / b; // Ok

			rv.setEntry(oB, B);
			rm.setEntry(oB, iphi0, dBdphi0); // C2-Ok
			rm.setEntry(oB, iP, dBdP); // C2-Ok
			rm.setEntry(oB, iF, dBdF); // C2-Ok
			rm.setEntry(oB, ib, dBdb); // C2-Ok
			rm.setEntry(oB, ieps, dBdeps); // C2-Ok

			rv.setEntry(oA, A); // C1-Ok
			rm.setEntry(oA, iphi0, dAdphi0); // C1-Ok
			rm.setEntry(oA, iP, dAdP); // C1-Ok
			rm.setEntry(oA, iF, dAdF); // C1-Ok
			rm.setEntry(oA, ib, dAdb); // C1-Ok
			rm.setEntry(oA, ieps, dAdeps); // C1-Ok

			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int iphi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mShell));
			final int iF = inputIndex(MatrixCorrectionModel2.shellLabel("F", mDatum, mShell));
			final int iP = inputIndex(MatrixCorrectionModel2.shellLabel("P", mDatum, mShell));
			final int ib = inputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mShell));
			final int ieps = inputIndex(MatrixCorrectionModel2.shellLabel(EPS, mDatum, mShell));
			checkIndices(iphi0, iF, iP, ib, ieps);

			final double phi0 = point.getEntry(iphi0);
			final double P = point.getEntry(iP);
			final double F = point.getEntry(iF);
			final double b = point.getEntry(ib);
			final double eps = point.getEntry(ieps);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final int oA = outputIndex(MatrixCorrectionModel2.shellLabel("A", mDatum, mShell));
			final int oB = outputIndex(MatrixCorrectionModel2.shellLabel("B", mDatum, mShell));
			checkIndices(oA, oB);
			// Page 62
			final double B = (b * b * F * (1.0 + eps) - P - phi0 * b * (2.0 + eps)) / eps; // C2-Ok
			rv.setEntry(oB, B);
			final double k1 = (1.0 + eps) / (eps * eps); // C1-Ok
			// Plugging B[b,F,eps,phi0,P] into the expression for A[B,b,F,eps,phi0,P] we get
			// A[b,F,eps,phi0,P].
			rv.setEntry(oA, k1 * (b * (b * F - 2.0 * phi0) - P) / b); // C1-Ok
			return rv;
		}

		@Override
		public String toString() {
			return "StepAB[" + mDatum + "," + mShell + "]";
		}
	}

	private static class StepAaBb //
			extends CompositeLabeledMultivariateJacobianFunction<EPMALabel> //
	{

		private static List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> buildSteps(
				final MatrixCorrectionDatum datum, final AtomicShell shell) {
			final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> res = new ArrayList<>();
			res.add(new StepRphi0(datum, shell));
			res.add(new StepFRbar(datum, shell));
			res.add(new StepPb(datum, shell));
			res.add(new Stepa(datum, shell));
			res.add(new StepEps(datum, shell));
			res.add(new StepAB(datum, shell));
			return res;
		}

		public StepAaBb(final MatrixCorrectionDatum datum, final AtomicShell shell) throws ArgumentException {
			super("StepAaBb", buildSteps(datum, shell));
		}

		@Override
		public String toString() {
			return "StepAaBb[]";
		}

	}

	private static class StepChi // Checked 16-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> { // C1

		private final MatrixCorrectionDatum mDatum;
		private final CharacteristicXRay mXRay;

		public StepChi(final MatrixCorrectionDatum datum, final CharacteristicXRay cxr) {
			super(buildInputs(datum, cxr), buildOutputs(datum, cxr));
			mDatum = datum;
			mXRay = cxr;
		}

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final CharacteristicXRay cxr) {
			return Collections.singletonList(MatrixCorrectionModel2.chiLabel(datum, cxr));
		}

		public static List<EPMALabel> buildInputs(final MatrixCorrectionDatum datum, final CharacteristicXRay cxr) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(matMacLabel(datum.getMaterial(), cxr));
			res.add(MatrixCorrectionModel2.takeOffAngleLabel(datum));
			return res;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final MatrixCorrectionDatumLabel toaT = MatrixCorrectionModel2.takeOffAngleLabel(mDatum);
			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = NullableRealMatrix.build(getOutputDimension(), getInputDimension());

			final int ochi = outputIndex(MatrixCorrectionModel2.chiLabel(mDatum, mXRay));
			checkIndices(ochi);

			final int toai = inputIndex(toaT);
			final double toa = point.getEntry(toai);
			assert (toa > 0.0) && (toa < 0.5 * Math.PI);
			final double csc = 1.0 / Math.sin(toa);

			final MaterialMAC macT = MatrixCorrectionModel2.matMacLabel(mDatum.getMaterial(), mXRay);
			final int maci = inputIndex(macT);
			final double mac = point.getEntry(maci);
			final double chi = mac * csc;
			rm.setEntry(ochi, maci, csc);
			rm.setEntry(ochi, toai, -1.0 * chi / Math.tan(toa)); // C1
			rv.setEntry(ochi, chi);

			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final int ochi = outputIndex(MatrixCorrectionModel2.chiLabel(mDatum, mXRay));
			checkIndices(ochi);

			final double toa = point.getEntry(inputIndex(MatrixCorrectionModel2.takeOffAngleLabel(mDatum)));
			assert (toa > 0.0) && (toa < 0.5 * Math.PI);
			final double csc = 1.0 / Math.sin(toa);
			final double mac = point
					.getEntry(inputIndex(MatrixCorrectionModel2.matMacLabel(mDatum.getMaterial(), mXRay)));
			final double chi = mac * csc; // C1
			rv.setEntry(ochi, chi);

			return rv;
		}

		@Override
		public String toString() {
			return "StepChi[" + mDatum + "," + mXRay + "]";
		}

	}

	private static class StepFx // Checked 16-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		public static List<EPMALabel> buildInputs(final MatrixCorrectionDatum datum, final CharacteristicXRay cxr) {
			final List<EPMALabel> res = new ArrayList<>();
			final AtomicShell shell = cxr.getInner();
			res.add(MatrixCorrectionModel2.shellLabel("A", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("B", datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel("b", datum, shell));
			res.add(MatrixCorrectionModel2.phi0Label(datum, shell));
			res.add(MatrixCorrectionModel2.shellLabel(EPS, datum, shell));
			res.add(MatrixCorrectionModel2.chiLabel(datum, cxr));
			res.add(MatrixCorrectionModel2.roughnessLabel(datum));
			return res;
		}

		public static List<EPMALabel> buildOutputs(final MatrixCorrectionDatum datum, final CharacteristicXRay cxr) {
			return Collections.singletonList(MatrixCorrectionModel2.FofChiLabel(datum, cxr));
		}

		private final MatrixCorrectionDatum mDatum;
		private final CharacteristicXRay mXRay;

		public StepFx(final MatrixCorrectionDatum unk, final CharacteristicXRay cxr) {
			super(buildInputs(unk, cxr), buildOutputs(unk, cxr));
			mDatum = unk;
			mXRay = cxr;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int iA = inputIndex(MatrixCorrectionModel2.shellLabel("A", mDatum, mXRay.getInner()));
			final int iB = inputIndex(MatrixCorrectionModel2.shellLabel("B", mDatum, mXRay.getInner()));
			final int ib = inputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mXRay.getInner()));
			final int iPhi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mXRay.getInner()));
			final int iChi = inputIndex(MatrixCorrectionModel2.chiLabel(mDatum, mXRay));
			final int ieps = inputIndex(MatrixCorrectionModel2.shellLabel(EPS, mDatum, mXRay.getInner()));
			final int itr = inputIndex(MatrixCorrectionModel2.roughnessLabel(mDatum));
			checkIndices(iA, iB, ib, iPhi0, iChi, ieps, itr);

			final double A = point.getEntry(iA);
			final double B = point.getEntry(iB);
			final double b = point.getEntry(ib);
			final double phi0 = point.getEntry(iPhi0);
			final double chi = point.getEntry(iChi);
			final double eps = point.getEntry(ieps);
			final double dz = point.getEntry(itr);

			final int oFx = outputIndex(MatrixCorrectionModel2.FofChiLabel(mDatum, mXRay));
			checkIndices(oFx);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final double expDzChi = Math.exp(-chi * dz);

			final double bpchi = b + chi;
			final double arg = chi + b * (1.0 + eps);
			final double powArg = Math.pow(arg, -2.0);
			final double powBpchi2 = Math.pow(bpchi, -2.0);

			final double Fx = expDzChi * (B / bpchi - (A * b * eps) / arg + phi0) / bpchi;
			final double dFxdA = -expDzChi * (b * eps) / (bpchi * arg);
			final double dFxdB = expDzChi * powBpchi2;
			final double dFxdb = (-expDzChi * (B * powBpchi2 + A * chi * eps * powArg) - Fx) / bpchi;
			final double dFxdphi0 = expDzChi / bpchi;
			final double dFxdchi = (expDzChi * ((A * b * eps) * powArg - B * powBpchi2) - (1 + bpchi * dz) * Fx)
					/ bpchi;
			final double dFxddz = -chi * Fx;
			final double dFxdeps = -expDzChi * A * b * powArg;

			rv.setEntry(oFx, Fx); // C2
			rm.setEntry(oFx, iA, dFxdA); // C2
			rm.setEntry(oFx, iB, dFxdB); // C2
			rm.setEntry(oFx, ib, dFxdb); // C2
			rm.setEntry(oFx, iPhi0, dFxdphi0); // C2
			rm.setEntry(oFx, iChi, dFxdchi); // C2
			rm.setEntry(oFx, ieps, dFxdeps); // C2
			rm.setEntry(oFx, itr, dFxddz);
			if (VALIDATE) {
				checkOptimized(this, point, rv);
				final RealVector dpt = point.mapMultiply(1.0e-6);
				if (itr >= 0)
					dpt.setEntry(itr, 1.0e-12);
				checkPartials(this, point, dpt, rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int iA = inputIndex(MatrixCorrectionModel2.shellLabel("A", mDatum, mXRay.getInner()));
			final int iB = inputIndex(MatrixCorrectionModel2.shellLabel("B", mDatum, mXRay.getInner()));
			final int ib = inputIndex(MatrixCorrectionModel2.shellLabel("b", mDatum, mXRay.getInner()));
			final int iPhi0 = inputIndex(MatrixCorrectionModel2.phi0Label(mDatum, mXRay.getInner()));
			final int iChi = inputIndex(MatrixCorrectionModel2.chiLabel(mDatum, mXRay));
			final int ieps = inputIndex(MatrixCorrectionModel2.shellLabel(EPS, mDatum, mXRay.getInner()));
			checkIndices(iA, iB, ib, iPhi0, iChi, ieps);

			final double A = point.getEntry(iA);
			final double B = point.getEntry(iB);
			final double b = point.getEntry(ib);
			final double phi0 = point.getEntry(iPhi0);
			final double chi = point.getEntry(iChi);
			final double eps = point.getEntry(ieps);
			final double dz = point.getEntry(inputIndex(MatrixCorrectionModel2.roughnessLabel(mDatum)));

			final int oFx = outputIndex(MatrixCorrectionModel2.FofChiLabel(mDatum, mXRay));
			checkIndices(oFx);

			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final double k0 = b + chi;
			final double k1 = chi + b * (1 + eps);
			// Must include dz here since z = 0 +- dz != 0
			final double Fx = Math.exp(-chi * dz) * (B / k0 - (A * b * eps) / k1 + phi0) / k0;

			rv.setEntry(oFx, Fx); // C2
			return rv;
		}

		@Override
		public String toString() {
			return "StepFx[" + mDatum + "," + mXRay + "]";
		}
	}

	private static class StepConductiveCoating //
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final MatrixCorrectionDatum mDatum;
		private final CharacteristicXRay mXRay;

		private static final List<EPMALabel> buildInputLabels(final MatrixCorrectionDatum datum,
				final CharacteristicXRay cxr) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.FofChiLabel(datum, cxr));
			if (datum.hasCoating()) {
				final Layer coating = datum.getCoating();
				res.add(MatrixCorrectionModel2.coatingMassThickness(coating));
				res.add(MatrixCorrectionModel2.matMacLabel(coating.getMaterial(), cxr));
				res.add(MatrixCorrectionModel2.takeOffAngleLabel(datum));
			}
			return res;

		}

		private static final List<EPMALabel> buildOutputLabels(//
				final MatrixCorrectionDatum datum, final CharacteristicXRay cxr //
		) {
			return Collections.singletonList(MatrixCorrectionModel2.FofChiReducedLabel(datum, cxr));
		}

		public StepConductiveCoating(final MatrixCorrectionDatum mcd, final CharacteristicXRay cxr) {
			super(buildInputLabels(mcd, cxr), buildOutputLabels(mcd, cxr));
			mDatum = mcd;
			mXRay = cxr;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());
			final int iFx = inputIndex(MatrixCorrectionModel2.FofChiLabel(mDatum, mXRay));
			final double Fx = point.getEntry(iFx);
			final int oFxRed = outputIndex(MatrixCorrectionModel2.FofChiReducedLabel(mDatum, mXRay));
			double coatTrans = 1.0;
			if (mDatum.hasCoating()) {
				final Layer coating = mDatum.getCoating();
				final int iMassTh = inputIndex(MatrixCorrectionModel2.coatingMassThickness(coating));
				// A simple model for absorption of x-rays by a thin coating...
				final double massTh = point.getEntry(iMassTh);
				final int itoa = inputIndex(MatrixCorrectionModel2.takeOffAngleLabel(mDatum));
				final int imu = inputIndex(MatrixCorrectionModel2.matMacLabel(coating.getMaterial(), mXRay));
				final double toa = point.getEntry(itoa);
				final double csc = 1.0 / Math.sin(toa);
				final double mu = point.getEntry(imu);
				coatTrans = Math.exp(-mu * massTh * csc);
				final double dcoatTransdmu = coatTrans * (-massTh * csc);
				final double dcoatTransdmassTh = coatTrans * (-mu * csc);
				final double dcoatTransdtoa = coatTrans * massTh * mu * csc * csc * Math.cos(toa);
				rm.setEntry(oFxRed, iMassTh, dcoatTransdmassTh * Fx);
				rm.setEntry(oFxRed, imu, dcoatTransdmu * Fx);
				rm.setEntry(oFxRed, itoa, dcoatTransdtoa * Fx);
				assert coatTrans > 0.9 : coatTrans;
			}
			rm.setEntry(oFxRed, iFx, coatTrans);
			rv.setEntry(oFxRed, coatTrans * Fx);

			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int iFx = inputIndex(MatrixCorrectionModel2.FofChiLabel(mDatum, mXRay));
			final int oFxRed = outputIndex(MatrixCorrectionModel2.FofChiReducedLabel(mDatum, mXRay));
			double coatTrans = 1.0;
			final double Fx = point.getEntry(iFx);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			if (mDatum.hasCoating()) {
				final Layer coating = mDatum.getCoating();
				final int iMassTh = inputIndex(MatrixCorrectionModel2.coatingMassThickness(coating));
				// A simple model for absorption of x-rays by a thin coating...
				final double massTh = point.getEntry(iMassTh);
				final int itoa = inputIndex(MatrixCorrectionModel2.takeOffAngleLabel(mDatum));
				final int imu = inputIndex(MatrixCorrectionModel2.matMacLabel(coating.getMaterial(), mXRay));
				final double toa = point.getEntry(itoa);
				final double csc = 1.0 / Math.sin(toa);
				final double mu = point.getEntry(imu);
				coatTrans = Math.exp(-mu * massTh * csc);
				assert coatTrans > 0.9 : //
				coatTrans;
			}
			rv.setEntry(oFxRed, coatTrans * Fx);
			return rv;
		}

		@Override
		public String toString() {
			final Layer coating = mDatum.getCoating();
			return "StepCoating[" + (coating != null ? "coating=" + coating.toString() : "Uncoated") + "]";
		}
	}

	private static class StepZA // Checked 16-Jan-2019
			extends LabeledMultivariateJacobianFunction<EPMALabel, EPMALabel> //
			implements ILabeledMultivariateFunction<EPMALabel, EPMALabel> {

		private final UnknownMatrixCorrectionDatum mUnknown;
		private final StandardMatrixCorrectionDatum mStandard;
		private final CharacteristicXRay mXRay;

		public static List<EPMALabel> buildInputs( //
				final UnknownMatrixCorrectionDatum unk, //
				final MatrixCorrectionDatum std, //
				final CharacteristicXRay cxr //
		) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.FofChiReducedLabel(unk, cxr));
			res.add(MatrixCorrectionModel2.shellLabel("F", unk, cxr.getInner()));
			res.add(MatrixCorrectionModel2.FofChiReducedLabel(std, cxr));
			res.add(MatrixCorrectionModel2.shellLabel("F", std, cxr.getInner()));
			return res;
		}

		public static List<EPMALabel> buildOutputs(final UnknownMatrixCorrectionDatum unk,
				final StandardMatrixCorrectionDatum std, final CharacteristicXRay cxr) {
			final List<EPMALabel> res = new ArrayList<>();
			res.add(MatrixCorrectionModel2.FxFLabel(unk, cxr));
			res.add(MatrixCorrectionModel2.FxFLabel(std, cxr));
			res.add(MatrixCorrectionModel2.zLabel(unk, std, cxr));
			res.add(MatrixCorrectionModel2.aLabel(unk, std, cxr));
			return res;
		}

		public StepZA(final UnknownMatrixCorrectionDatum unk, final StandardMatrixCorrectionDatum std,
				final CharacteristicXRay cxr) {
			super(buildInputs(unk, std, cxr), buildOutputs(unk, std, cxr));
			mUnknown = unk;
			mStandard = std;
			mXRay = cxr;
		}

		@Override
		public Pair<RealVector, RealMatrix> value(final RealVector point) {
			final int iFxu = inputIndex(MatrixCorrectionModel2.FofChiReducedLabel(mUnknown, mXRay));
			final int iFxs = inputIndex(MatrixCorrectionModel2.FofChiReducedLabel(mStandard, mXRay));
			final int iFu = inputIndex(MatrixCorrectionModel2.shellLabel("F", mUnknown, mXRay.getInner()));
			final int iFs = inputIndex(MatrixCorrectionModel2.shellLabel("F", mStandard, mXRay.getInner()));

			checkIndices(iFxu, iFxs, iFu, iFs);

			final double Fxu = point.getEntry(iFxu);
			final double Fxs = point.getEntry(iFxs);
			final double Fu = point.getEntry(iFu);
			final double Fs = point.getEntry(iFs);

			final RealVector rv = new ArrayRealVector(getOutputDimension());
			final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());

			final int oFxFu = outputIndex(MatrixCorrectionModel2.FxFLabel(mUnknown, mXRay));
			final int oFxFs = outputIndex(MatrixCorrectionModel2.FxFLabel(mStandard, mXRay));
			final int oZ = outputIndex(MatrixCorrectionModel2.zLabel(mUnknown, mStandard, mXRay));
			final int oA = outputIndex(MatrixCorrectionModel2.aLabel(mUnknown, mStandard, mXRay));
			checkIndices(oFxFu, oFxFs, oZ, oA);

			final double a = (Fs * Fxu) / (Fu * Fxs);
			rv.setEntry(oA, a); // C2
			rm.setEntry(oA, iFxu, a / Fxu); // C2
			rm.setEntry(oA, iFxs, -a / Fxs); // C2
			rm.setEntry(oA, iFs, a / Fs); // C2
			rm.setEntry(oA, iFu, -a / Fu); // C2

			final double z = Fu / Fs;
			rv.setEntry(oZ, z); // C2
			rm.setEntry(oZ, iFs, -z / Fs); // C2
			rm.setEntry(oZ, iFu, 1.0 / Fs); // C2

			final double FxFu = Fxu / Fu;
			rv.setEntry(oFxFu, FxFu); // C2
			rm.setEntry(oFxFu, iFu, -FxFu / Fu); // C2
			rm.setEntry(oFxFu, iFxu, 1.0 / Fu); // C2

			final double FxFs = Fxs / Fs;
			rv.setEntry(oFxFs, FxFs); // C2
			rm.setEntry(oFxFs, iFs, -FxFs / Fs); // C2
			rm.setEntry(oFxFs, iFxs, 1.0 / Fs); // C2

			if (VALIDATE) {
				checkOptimized(this, point, rv);
				checkPartials(this, point, point.mapMultiply(1.0e-6), rm);
			}
			return Pair.create(rv, rm);
		}

		@Override
		public RealVector optimized(final RealVector point) {
			final int iFxu = inputIndex(MatrixCorrectionModel2.FofChiReducedLabel(mUnknown, mXRay));
			final int iFxs = inputIndex(MatrixCorrectionModel2.FofChiReducedLabel(mStandard, mXRay));
			final int iFu = inputIndex(MatrixCorrectionModel2.shellLabel("F", mUnknown, mXRay.getInner()));
			final int iFs = inputIndex(MatrixCorrectionModel2.shellLabel("F", mStandard, mXRay.getInner()));
			checkIndices(iFxu, iFxs, iFu, iFs);

			final double Fxu = point.getEntry(iFxu);
			final double Fxs = point.getEntry(iFxs);
			final double Fu = point.getEntry(iFu);
			final double Fs = point.getEntry(iFs);

			final RealVector rv = new ArrayRealVector(getOutputDimension());

			final int oFxFu = outputIndex(MatrixCorrectionModel2.FxFLabel(mUnknown, mXRay));
			final int oFxFs = outputIndex(MatrixCorrectionModel2.FxFLabel(mStandard, mXRay));
			final int oZ = outputIndex(MatrixCorrectionModel2.zLabel(mUnknown, mStandard, mXRay));
			final int oA = outputIndex(MatrixCorrectionModel2.aLabel(mUnknown, mStandard, mXRay));
			checkIndices(oFxFu, oFxFs, oZ, oA);

			rv.setEntry(oA, (Fs * Fxu) / (Fu * Fxs)); // C2
			rv.setEntry(oZ, (Fu / Fs)); // C2
			rv.setEntry(oFxFu, Fxu / Fu); // C2
			rv.setEntry(oFxFs, Fxs / Fs); // C2
			return rv;
		}

		@Override
		public String toString() {
			return "StepZA[" + mStandard + "," + mUnknown + "," + mXRay + "]";
		}
	}

	/**
	 * This class performs the full XPP calculation for a single composition and the
	 * associated set of characteristic x-rays. It breaks the calculation into a
	 * series of steps which involve 1) only the composition; 2) the composition and
	 * the atomic shells; and 3) the composition and the characteristic x-rays. It
	 * is designed to break the calculation down into small independent blocks each
	 * of which is relatively fast to calculate. This minimizes and postpones the
	 * need to build and copy large Jacobian matrices and is thus slightly more
	 * computationally efficient.
	 *
	 * @author Nicholas W. M. Ritchie
	 */
	private static final class StepXPP //
			extends CompositeLabeledMultivariateJacobianFunction<EPMALabel> //
	{

		private static List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> buildSteps(
				final MatrixCorrectionDatum datum, final CharacteristicXRaySet exrs) throws ArgumentException {
			final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> res = new ArrayList<>();
			res.add(new StepMJZBarb(datum.getMaterial(), datum instanceof StandardMatrixCorrectionDatum));
			final Set<AtomicShell> shells = exrs.getSetOfInnerAtomicShells();
			{
				final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> step = new ArrayList<>();
				for (final AtomicShell shell : shells)
					step.add(new StepQlaE0OneOverS(datum, shell));
				res.add(LabeledMultivariateJacobianFunctionBuilder.join("QlaOoS", step));
			}
			{
				final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> step = new ArrayList<>();
				for (final AtomicShell shell : shells)
					step.add(new StepAaBb(datum, shell));
				res.add(LabeledMultivariateJacobianFunctionBuilder.join("AaBb", step));
			}
			{
				final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> step = new ArrayList<>();
				for (final CharacteristicXRay cxr : exrs.getSetOfCharacteristicXRay())
					step.add(new StepChi(datum, cxr));
				res.add(LabeledMultivariateJacobianFunctionBuilder.join("Chi", step));
			}
			{
				final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> step = new ArrayList<>();
				for (final CharacteristicXRay cxr : exrs.getSetOfCharacteristicXRay())
					step.add(new StepFx(datum, cxr));
				res.add(LabeledMultivariateJacobianFunctionBuilder.join("Fx", step));
			}
			{
				final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> step = new ArrayList<>();
				for (final CharacteristicXRay cxr : exrs.getSetOfCharacteristicXRay())
					step.add(new StepConductiveCoating(datum, cxr));
				res.add(LabeledMultivariateJacobianFunctionBuilder.join("Coating", step));
			}
			return res;
		}

		public StepXPP(final MatrixCorrectionDatum datum, final CharacteristicXRaySet cxrs) throws ArgumentException {
			super("XPP[" + datum + "]", buildSteps(datum, cxrs));
		}

		@Override
		public String toString() {
			return "StepXPP[]";
		}

	};

	/**
	 * This method joins together the individual Composition computations into a
	 * single final step that outputs the final matrix corrections.
	 *
	 * @param unk
	 * @param stds
	 * @return List&lt;NamedMultivariateJacobianFunction&gt;
	 * @throws ArgumentException
	 */
	private static List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> buildSteps( //
			final Set<KRatioLabel> kratios //
	) throws ArgumentException {
		final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> res = new ArrayList<>();
		final Map<UnknownMatrixCorrectionDatum, CharacteristicXRaySet> unks = new HashMap<>();
		final Map<StandardMatrixCorrectionDatum, CharacteristicXRaySet> stds = new HashMap<>();
		final Set<Composition> allComps = new HashSet<>();
		for (final KRatioLabel krl : kratios) {
			assert krl.getMethod().equals(Method.Measured);
			final UnknownMatrixCorrectionDatum unk = krl.getUnknown();
			final StandardMatrixCorrectionDatum std = krl.getStandard();
			final ElementXRaySet exrs = krl.getXRaySet();
			if (!unks.containsKey(unk))
				unks.put(unk, new CharacteristicXRaySet());
			unks.get(unk).addAll(exrs);
			if (!stds.containsKey(std))
				stds.put(std, new CharacteristicXRaySet());
			stds.get(std).addAll(exrs);
			allComps.add(std.getComposition());
			if (std.hasCoating())
				allComps.add(std.getCoating().getComposition());
			if (unk.hasCoating())
				allComps.add(unk.getCoating().getComposition());
		}
		for (final Composition comp : allComps)
			res.add(comp.getFunction());
		for (final UnknownMatrixCorrectionDatum unk : unks.keySet())
			res.add(buildMaterialMACFunctions(unk.getMaterial(), kratios));
		{
			final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> step = new ArrayList<>();
			final CharacteristicXRaySet cxrs = new CharacteristicXRaySet();
			for (final Map.Entry<StandardMatrixCorrectionDatum, CharacteristicXRaySet> me : stds.entrySet()) {
				step.add(new StepXPP(me.getKey(), me.getValue()));
				cxrs.addAll(me.getValue());
			}
			for (final Map.Entry<UnknownMatrixCorrectionDatum, CharacteristicXRaySet> me : unks.entrySet())
				step.add(new StepXPP(me.getKey(), me.getValue()));
			res.add(LabeledMultivariateJacobianFunctionBuilder.join("XPP", step));
		}
		{
			final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends EPMALabel>> step = new ArrayList<>();
			for (final KRatioLabel krl : kratios)
				for (final CharacteristicXRay cxr : krl.getXRaySet().getSetOfCharacteristicXRay())
					step.add(new StepZA(krl.getUnknown(), krl.getStandard(), cxr));
			res.add(LabeledMultivariateJacobianFunctionBuilder.join("ZA", step));
		}
		// Need to do it this way to ensure that certain items aren't double
		// calculated...
		res.add(new MultiE0MultiLineModel(kratios));
		return res;
	}

	/**
	 * Returns a very basic set of output that includes the ZAF, Z, A terms for each
	 * k-ratio in the set.
	 *
	 *
	 * @param kratios Set&lt;EPMALabel&gt;
	 * @return List<EPMALabel>
	 */
	static public List<EPMALabel> buildDefaultOutputs(final Set<KRatioLabel> kratios) {
		final FastIndex<EPMALabel> res = new FastIndex<>();
		for (final KRatioLabel krl : kratios) {
			res.add(MatrixCorrectionModel2.zafLabel(krl));
			for (final CharacteristicXRay cxr : krl.getXRaySet().getSetOfCharacteristicXRay()) {
				res.add(MatrixCorrectionModel2.zLabel(krl.getUnknown(), krl.getStandard(), cxr));
				res.add(MatrixCorrectionModel2.aLabel(krl.getUnknown(), krl.getStandard(), cxr));
			}
		}
		return res;
	}

	/**
	 * @param kratios      Set of KRatioLabel objects.
	 * @param outputLabels
	 * @throws ArgumentException
	 */
	public XPPMatrixCorrection2(//
			final Set<KRatioLabel> kratios, //
			final List<EPMALabel> outputLabels //
	) throws ArgumentException {
		super("XPP Matrix Correction", kratios, buildSteps(kratios), outputLabels);
	}

	/**
	 * Mean ionization potential
	 *
	 * @param elm
	 * @return {@link UncertainValue}
	 */
	public UncertainValue computeJi(final Element elm) {
		final double z = elm.getAtomicNumber();
		final double j = 1.0e-3 * z * (10.04 + 8.25 * Math.exp(-z / 11.22));
		return new UncertainValue(j, "J[" + elm.getAbbrev() + "]", 0.03 * j);
	}

	/**
	 * Computes the ionization exponent ('m') in the ionization cross section model
	 * Qla(U) = log(U)/(U<sup>m</sup> El<sup>2</sup>).
	 *
	 * @param sh   AtomicShell
	 * @param frac A scale for the relative uncertainty
	 * @return UncertainValue
	 */
	public static UncertainValue computeIonizationExponent(final AtomicShell sh, final double frac) {
		double m, dm;
		switch (sh.getFamily()) {
		case K:
			m = 0.86 + 0.12 * Math.exp(-Math.pow(sh.getElement().getAtomicNumber() / 5, 2.0));
			dm = m * 0.01;
			break;
		case L:
			m = 0.82;
			dm = 0.02 * m;
			break;
		case M:
		case N:
		default:
			m = 0.78;
			dm = 0.05 * m;
			break;
		}
		return new UncertainValue(m, frac * dm);
	}

	/**
	 * This is a quick way to build many of the input parameters. Many of the input
	 * parameters are computed or tabulated. Some are input as experimental
	 * conditions like beam energy which are provided in the {@link KRatioLabel}
	 * objects. Using this information, it is possible to calculate all the
	 * necessary inputs to this {@link LabeledMultivariateJacobianFunction}.
	 *
	 * @param estUnknown
	 * @return {@link UncertainValues}
	 * @throws ArgumentException
	 */
	@Override
	public UncertainValues<EPMALabel> buildInput(final UncertainValues<MassFraction> estUnknown) //
			throws ArgumentException {
		final UncertainValues<EPMALabel> bp = buildParameters(estUnknown);
		return bp;
	}

	/**
	 * Many of the input parameters are computed or tabulated. Some are input as
	 * experimental conditions like beam energy.
	 *
	 * @param withUnc true to return parameters with uncertainties or false for
	 *                those without
	 * @return UncertainValues object containing either the variable quantities or
	 *         the constant quantities
	 * @throws ArgumentException
	 */
	private UncertainValues<EPMALabel> buildParameters( //
			final UncertainValues<MassFraction> estUnknown) //
			throws ArgumentException {
		final List<UncertainValuesBase<? extends EPMALabel>> results = new ArrayList<>();
		// Make sure that there are no replicated Compositions
		{
			assert estUnknown != null;
			final Map<EPMALabel, Number> unkMap = new HashMap<>();
			for (final MassFraction mf : estUnknown.getLabels()) {
				final Material unkMat = mf.getMaterial();
				for (final Element elm : unkMat.getElementSet())
					unkMap.put(MaterialLabel.buildAtomicWeightTag(unkMat, elm), unkMat.getAtomicWeight(elm));
			}
			results.add(new UncertainValues<>(unkMap));
			results.add(estUnknown);
		}
		{
			final Set<Composition> allComps = new HashSet<>();
			for (final KRatioLabel krl : mKRatios) {
				allComps.add(krl.getStandard().getComposition());
				for (final MatrixCorrectionDatum mcd : Arrays.asList(krl.getStandard(), krl.getUnknown())) {
					if (mcd.hasCoating())
						allComps.add(mcd.getCoating().getComposition());
				}
			}
			// Add the mass fraction and atomic weight tags
			for (final Composition comp : allComps)
				results.add(comp.getInputs());
		}

		results.add(buildMeanIonizationPotentials());
		results.add(buildElementalMACs(estUnknown));
		{
			final Map<EPMALabel, Number> vals = new HashMap<>();
			for (final KRatioLabel krl : mKRatios) {
				{
					final UnknownMatrixCorrectionDatum mcd = krl.getUnknown();
					vals.put(MatrixCorrectionModel2.beamEnergyLabel(mcd), mcd.getBeamEnergy());
				}
				{
					final StandardMatrixCorrectionDatum mcd = krl.getStandard();
					vals.put(MatrixCorrectionModel2.beamEnergyLabel(mcd), mcd.getBeamEnergy());
				}
			}
			results.add(new UncertainValues<>(vals));
		}
		final Set<Layer> coatings = new HashSet<>();
		for (final KRatioLabel krl : mKRatios) {
			if (krl.getStandard().hasCoating())
				coatings.add(krl.getStandard().getCoating());
			if (krl.getUnknown().hasCoating())
				coatings.add(krl.getUnknown().getCoating());
		}
		if (coatings.size() > 0) {
			final Map<EPMALabel, Number> vals = new HashMap<>();
			for (final Layer coating : coatings)
				vals.put(MatrixCorrectionModel2.coatingMassThickness(coating), coating.getMassThickness());
			results.add(new UncertainValues<>(vals));
		}
		{
			final Map<EPMALabel, Number> vals = new HashMap<>();
			final Set<AtomicShell> allShells = new HashSet<>();
			for (final KRatioLabel krl : mKRatios)
				for (final XRay cxr : krl.getXRaySet())
					allShells.add(((CharacteristicXRay) cxr).getInner());
			for (final AtomicShell sh : allShells)
				vals.put(new IonizationExponentLabel(sh), computeIonizationExponent(sh, 1.0));
			assert !vals.isEmpty();
			results.add(new UncertainValues<>(vals));
		}
		{
			final Map<EPMALabel, Number> vals = new HashMap<>();
			for (final KRatioLabel krl : mKRatios) {
				vals.put(MatrixCorrectionModel2.takeOffAngleLabel(krl.getUnknown()),
						krl.getUnknown().getTakeOffAngle());
				vals.put(MatrixCorrectionModel2.takeOffAngleLabel(krl.getStandard()),
						krl.getStandard().getTakeOffAngle());
			}
			results.add(new UncertainValues<>(vals));
		}
		{
			final Map<EPMALabel, Number> vals = new HashMap<>();
			for (final KRatioLabel krl : mKRatios) {
				vals.put(MatrixCorrectionModel2.roughnessLabel(krl.getUnknown()),
						new UncertainValue(0.0, krl.getUnknown().getRoughness()));
				vals.put(MatrixCorrectionModel2.roughnessLabel(krl.getStandard()),
						new UncertainValue(0.0, krl.getStandard().getRoughness()));
			}
			if (!vals.isEmpty())
				results.add(new UncertainValues<>(vals));
		}
		final CharacteristicXRaySet allCxr = new CharacteristicXRaySet();
		for (final KRatioLabel krl : mKRatios)
			allCxr.addAll(krl.getXRaySet());
		final int sz = allCxr.size();
		if (sz > 0) {
			final Map<EPMALabel, Number> weightT = new HashMap<>();
			for (final CharacteristicXRay cxr : allCxr.getSetOfCharacteristicXRay())
				weightT.put(new MatrixCorrectionModel2.XRayWeightLabel(cxr), cxr.getWeightUV());
			results.add(new UncertainValues<>(weightT));
		}
		final Map<EPMALabel, Number> mon = new HashMap<>();
		for (final KRatioLabel krl : mKRatios) {
			for (final MatrixCorrectionDatum mcd : Arrays.asList(krl.getStandard(), krl.getUnknown()))
				for (final CharacteristicXRay cxr : krl.getXRaySet().getSetOfCharacteristicXRay()) {
					final EPMALabel sfLbl = new SecondaryFluorescenceModel.SecondaryFluorescenceLabel(mcd,
							cxr.getInner());
					if (!mon.containsKey(sfLbl))
						mon.put(sfLbl, new UncertainValue(1.0, 0.01));
				}
		}
		results.add(new UncertainValues<>(mon));
		return UncertainValues.<EPMALabel>force(UncertainValuesBase.combine(results, true));
	}

	public Set<Element> getElements() {
		final Set<Element> elms = new TreeSet<>();
		for (final KRatioLabel krl : mKRatios)
			elms.add(krl.getElement());
		return elms;
	}

	private static LabeledMultivariateJacobianFunction<EPMALabel, MaterialMAC> buildMaterialMACFunctions( //
			final Material estUnknown, //
			final Set<KRatioLabel> kratios) //
			throws ArgumentException {
		final Map<Material, CharacteristicXRaySet> comps = new HashMap<>();
		final List<LabeledMultivariateJacobianFunction<? extends EPMALabel, ? extends MaterialMAC>> funcs = new ArrayList<>();
		{
			final Set<CharacteristicXRay> allCxr = new HashSet<>();
			comps.put(estUnknown, new CharacteristicXRaySet());
			for (final KRatioLabel krl : kratios) {
				// assert (estUnknown == null) ||
				// estUnknown.getElementSet().equals(krl.getUnknown().getElementSet());
				comps.get(estUnknown).addAll(krl.getXRaySet());
				if (krl.getUnknown().hasCoating()) {
					final Material mat = krl.getUnknown().getCoating().getMaterial();
					if (!comps.containsKey(mat))
						comps.put(mat, new CharacteristicXRaySet());
					comps.get(mat).addAll(krl.getXRaySet());
				}
				{
					final Material mat = krl.getStandard().getMaterial();
					if (!comps.containsKey(mat))
						comps.put(mat, new CharacteristicXRaySet());
					comps.get(mat).addAll(krl.getXRaySet());
				}
				if (krl.getStandard().hasCoating()) {
					final Material mat = krl.getStandard().getCoating().getMaterial();
					if (!comps.containsKey(mat))
						comps.put(mat, new CharacteristicXRaySet());
					comps.get(mat).addAll(krl.getXRaySet());
				}
				allCxr.addAll(krl.getXRaySet().getSetOfCharacteristicXRay());
			}
			for (final CharacteristicXRay cxr : allCxr) {
				final Set<Material> mats = new HashSet<>();
				for (final Map.Entry<Material, CharacteristicXRaySet> me : comps.entrySet())
					if (me.getValue().contains(cxr))
						mats.add(me.getKey());
				// assert mats.size() > 1;
				funcs.add(new MaterialMACFunction(new ArrayList<>(mats), cxr));
			}
		}
		return LabeledMultivariateJacobianFunctionBuilder.join("MaterialMACs", funcs);
	}

	private UncertainValues<EPMALabel> buildElementalMACs(//
			final UncertainValues<MassFraction> estUnknown) //
			throws ArgumentException {
		final Set<Element> elms = new HashSet<>();
		final Set<CharacteristicXRay> scxr = new HashSet<>();
		{
			for (final KRatioLabel krl : mKRatios) {
				for (final MatrixCorrectionDatum mcd : Arrays.asList(krl.getStandard(), krl.getUnknown())) {
					elms.addAll(mcd.getMaterial().getElementSet());
					if (mcd.hasCoating())
						elms.addAll(mcd.getCoating().getMaterial().getElementSet());
				}
				scxr.addAll(krl.getXRaySet().getSetOfCharacteristicXRay());
			}
		}
		final Map<EPMALabel, Number> macInps = new HashMap<>();
		final ElementalMAC em = new ElementalMAC();
		for (final Element elm : elms)
			for (final CharacteristicXRay cxr : scxr)
				macInps.put(new ElementalMAC.ElementMAC(elm, cxr), em.compute(elm, cxr));
		return new UncertainValues<EPMALabel>(macInps);
	}

	private UncertainValues<EPMALabel> buildMeanIonizationPotentials() {
		final Set<Element> elms = new HashSet<>();
		for (final KRatioLabel krl : mKRatios) {
			elms.addAll(krl.getStandard().getMaterial().getElementSet());
			elms.addAll(krl.getUnknown().getMaterial().getElementSet());
		}
		final RealVector vals = new ArrayRealVector(elms.size());
		final RealVector var = new ArrayRealVector(vals.getDimension());
		final List<EPMALabel> tags = new ArrayList<>();
		int i = 0;
		for (final Element elm : elms) {
			tags.add(MatrixCorrectionModel2.meanIonizationLabel(elm));
			final UncertainValue j = computeJi(elm);
			vals.setEntry(i, j.doubleValue());
			var.setEntry(i, j.variance());
			++i;
		}
		assert tags.size() == vals.getDimension();
		final UncertainValues<EPMALabel> mip = new UncertainValues<EPMALabel>(tags, vals, var);
		return mip;
	}

	private double phiRhoZ(final double rhoZ, final double a, final double b, final double A, final double B,
			final double phi0) {
		return A * Math.exp(-a * rhoZ) + (B * rhoZ + phi0 - A) * Math.exp(-b * rhoZ);
	}

	public DataFrame<Double> computePhiRhoZCurve( //
			final Map<EPMALabel, Double> outputs, //
			final double rhoZmax, //
			final double dRhoZ, //
			final double minWeight //
	) {
		final DataFrame<Double> res = new DataFrame<>();
		{
			final List<Double> vals = new ArrayList<>();
			for (double rhoZ = 0.0; rhoZ <= rhoZmax; rhoZ += dRhoZ)
				vals.add(rhoZ);
			res.add("rhoZ", vals);
		}
		for (final KRatioLabel krl : mKRatios) {
			for (final CharacteristicXRay cxr : krl.getXRaySet().getSetOfCharacteristicXRay()) {
				if (cxr.getWeight() > minWeight) {
					{
						final StandardMatrixCorrectionDatum std = krl.getStandard();
						final double a = outputs.get(MatrixCorrectionModel2.shellLabel("a", std, cxr.getInner()))
								.doubleValue();
						final double b = outputs.get(MatrixCorrectionModel2.shellLabel("b", std, cxr.getInner()))
								.doubleValue();
						final double A = outputs.get(MatrixCorrectionModel2.shellLabel("A", std, cxr.getInner()))
								.doubleValue();
						final double B = outputs.get(MatrixCorrectionModel2.shellLabel("B", std, cxr.getInner()))
								.doubleValue();
						final double chi = outputs.get(new MatrixCorrectionModel2.ChiLabel(std, cxr)).doubleValue();
						final double phi0 = outputs.get(new MatrixCorrectionModel2.Phi0Label(std, cxr.getInner()))
								.doubleValue();
						final List<Double> vals1 = new ArrayList<>();
						final List<Double> vals2 = new ArrayList<>();
						for (double rhoZ = 0.0; rhoZ <= rhoZmax; rhoZ += dRhoZ) {
							final double prz = phiRhoZ(rhoZ, a, b, A, B, phi0);
							vals1.add(prz);
							vals2.add(prz * Math.exp(-chi * rhoZ));
						}
						res.add(std.toString() + "[" + cxr.toString() + ", gen]", vals1);
						res.add(std.toString() + "[" + cxr.toString() + ", emit]", vals2);
					}
					{
						final UnknownMatrixCorrectionDatum unk = krl.getUnknown();
						final double a = outputs.get(MatrixCorrectionModel2.shellLabel("a", unk, cxr.getInner()))
								.doubleValue();
						final double b = outputs.get(MatrixCorrectionModel2.shellLabel("b", unk, cxr.getInner()))
								.doubleValue();
						final double A = outputs.get(MatrixCorrectionModel2.shellLabel("A", unk, cxr.getInner()))
								.doubleValue();
						final double B = outputs.get(MatrixCorrectionModel2.shellLabel("B", unk, cxr.getInner()))
								.doubleValue();
						final double chi = outputs.get(new MatrixCorrectionModel2.ChiLabel(unk, cxr)).doubleValue();
						final double phi0 = outputs.get(new MatrixCorrectionModel2.Phi0Label(unk, cxr.getInner()))
								.doubleValue();
						final List<Double> vals1 = new ArrayList<>();
						final List<Double> vals2 = new ArrayList<>();
						for (double rhoZ = 0.0; rhoZ <= rhoZmax; rhoZ += dRhoZ) {
							final double prz = phiRhoZ(rhoZ, a, b, A, B, phi0);
							vals1.add(prz);
							vals2.add(prz * Math.exp(-chi * rhoZ));
						}
						res.add(unk.toString() + "[" + cxr.toString() + ", gen]", vals1);
						res.add(unk.toString() + "[" + cxr.toString() + ", emit]", vals2);
					}
				}
			}
		}
		return res;
	}

	/**
	 * Useful for calculating the
	 *
	 * @param inputs
	 * @param frac
	 * @return
	 */
	public RealVector delta(final UncertainValuesBase<EPMALabel> inputs, final double frac) {
		final RealVector res = inputs.getValues().copy();
		for (final EPMALabel lbl : inputs.getLabels(MatrixCorrectionModel2.RoughnessLabel.class))
			res.setEntry(inputs.indexOf(lbl), 1.0e-7);
		for (final EPMALabel lbl : inputs.getLabels(MatrixCorrectionModel2.CoatingThickness.class))
			res.setEntry(inputs.indexOf(lbl), 1.0e-7);
		for (int i = 0; i < res.getDimension(); ++i)
			if (Math.abs(res.getEntry(i)) < 1.0e-20)
				res.setEntry(i, 1.0e-10);
		return res.mapMultiply(frac);
	}

	@Override
	public String toHTML(final Mode mode) {
		final Report r = new Report("XPP");
		r.addHeader("XPP Matrix Correction");
		final Table tbl = new Table();
		tbl.addRow(Table.th("Unknown"), Table.th("Standard"), Table.th("Transitions"));
		for (final KRatioLabel krl : mKRatios)
			tbl.addRow(Table.td(krl.getUnknown()), Table.td(krl.getStandard()), Table.td(krl.getXRaySet()));
		r.add(tbl);
		r.addSubHeader("Inputs / Outputs");
		r.addHTML(super.toHTML(mode));
		return r.toHTML(mode);
	}

	@Override
	public String toString() {
		return "XPPMatrixCorrection2";
	}
}
