package gov.nist.microanalysis.roentgen.physics.composition;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import com.duckandcover.html.HTML;
import com.duckandcover.html.Table;
import com.duckandcover.html.Table.Item;
import com.google.common.base.Objects;

import gov.nist.microanalysis.roentgen.ArgumentException;
import gov.nist.microanalysis.roentgen.math.uncertainty.BaseTag;
import gov.nist.microanalysis.roentgen.math.uncertainty.NamedMultivariateJacobianFunction;
import gov.nist.microanalysis.roentgen.math.uncertainty.UncertainValue;
import gov.nist.microanalysis.roentgen.math.uncertainty.UncertainValues;
import gov.nist.microanalysis.roentgen.physics.Element;
import gov.nist.microanalysis.roentgen.utility.BasicNumberFormat;

/**
 * <p>
 * The Composition object is a specialization of UncertainValues for handling
 * measures of material composition. There are various different ways of
 * expressing composition and Composition tries to handle the common ones - mass
 * fraction, normalized mass fraction, atom fraction and stoichiometry.
 * </p>
 * <p>
 * Each representation attempts to store the values and covariances in the best
 * possible internal representation. Conversion between representations is
 * possible using asMassFraction, asAtomFraction, asNormalizedMassFraction.
 * </p>
 *
 * @author Nicholas
 * @version 1.0
 */

public class Composition
   extends
   UncertainValues {

   public enum Representation {
      MassFraction, NormalizedMassFraction, AtomFraction, Stoichiometry,
   };

   static public BasicNumberFormat MASS_FRACTION_FORMAT = new BasicNumberFormat("0.0000");
   static public BasicNumberFormat MASS_FRACTION_FORMAT_LONG = new BasicNumberFormat("0.00000");
   static public BasicNumberFormat MEAN_Z_FORMAT = new BasicNumberFormat("0.00");
   static public BasicNumberFormat ATOMIC_FRACTION_FORMAT = new BasicNumberFormat("0.0###");
   static public BasicNumberFormat ATOMIC_FRACTION_FORMAT_LONG = new BasicNumberFormat("0.00E0");

   private final String mHTML;
   private final Representation mRepresentation;

   private static class ElementTag
      extends
      BaseTag {
      public ElementTag(final String prefix, final String html, final Element elm) {
         super(prefix, "<html>" + html, elm);
      }

      public Element getElement() {
         return (Element) getObject2();
      }

      public String getHTML() {
         return (String) getObject1();
      }
   }

   public static class MassFractionTag
      extends
      ElementTag {
      private MassFractionTag(final String html, final Element elm) {
         super("C", html, elm);
      }
   }

   public static Object buildMassFractionTag(final Composition comp, final Element elm) {
      return new MassFractionTag(comp.mHTML, elm);
   }

   public static class NormalizedMassFractionTag
      extends
      ElementTag {
      private NormalizedMassFractionTag(final String html, final Element elm) {
         super("N", html, elm);
      }
   }

   public static Object buildNormalizedMassFractionTag(final Composition comp, final Element elm) {
      return new NormalizedMassFractionTag(comp.mHTML, elm);
   }

   public static class AtomFractionTag
      extends
      ElementTag {
      private AtomFractionTag(final String html, final Element elm) {
         super("A", html, elm);
      }
   }

   public static Object buildAtomFractionTag(final Composition comp, final Element elm) {
      return new AtomFractionTag(comp.mHTML, elm);
   }

   public static class StoichiometryTag
      extends
      ElementTag {
      private StoichiometryTag(final String html, final Element elm) {
         super("S", html, elm);
      }
   }

   public static Object buildStoichiometryTag(final Composition comp, final Element elm) {
      return new StoichiometryTag(comp.mHTML, elm);
   }

   private static List<Object> buildTags(final Composition comp, final Representation mode) {
      return buildTags(comp.mHTML, comp.getElementList(), mode);
   }

   private static List<Object> buildTags(final String html, final List<Element> elms, final Representation mode) {
      final List<Object> res = new ArrayList<>();
      for(final Element elm : elms) {
         switch(mode) {
            case MassFraction:
               res.add(new MassFractionTag(html, elm));
               break;
            case NormalizedMassFraction:
               res.add(new NormalizedMassFractionTag(html, elm));
               break;
            case AtomFraction:
               res.add(new AtomFractionTag(html, elm));
               break;
            case Stoichiometry:
               res.add(new StoichiometryTag(html, elm));
               break;
         }
      }
      return res;
   }

   /**
    * I'm not sure this is a good idea...
    *
    * @param rv
    * @return RealVector
    */
   private static RealVector imposeNonNegative(final RealVector rv) {
      final RealVector res = new ArrayRealVector(rv);
      for(int i = 0; i < res.getDimension(); ++i)
         if(res.getEntry(i) < 0.0)
            res.setEntry(i, 0.0);
      return res;
   }

   private Composition(final Representation mode, final String html, final List<Element> elms, final RealVector vals, final RealVector vars) {
      super(buildTags(html, elms, mode), imposeNonNegative(vals), vars);
      mHTML = html;
      mRepresentation = mode;
   }

   private Composition(final Representation mode, final String html, final List<Element> elms, final RealVector vals, final RealMatrix cov) {
      super(buildTags(html, elms, mode), imposeNonNegative(vals), cov);
      mHTML = html;
      mRepresentation = mode;
   }

   private static RealVector buildValues(final Map<Element, Integer> stoic) {
      final RealVector rv = new ArrayRealVector(stoic.size());
      int i = 0;
      for(final Element elm : stoic.keySet()) {
         rv.setEntry(i, stoic.get(elm));
         ++i;
      }
      return rv;
   }

   private Composition(final Representation mode, final String html, final Map<Element, Integer> stoic) {
      super(buildTags(html, new ArrayList<>(stoic.keySet()), mode), buildValues(stoic), MatrixUtils.createRealMatrix(stoic.size(), stoic.size()));
      mHTML = html;
      mRepresentation = mode;
   }

   public static Composition stoichiometry(final String html, final Map<Element, Integer> stoic) {
      return new Composition(Representation.Stoichiometry, html, stoic);
   }

   public static Composition massFraction(final String html, final List<Element> elms, final RealVector vals, final RealVector vars) {
      assert elms.size() == vals.getDimension();
      assert elms.size() == vars.getDimension();
      return new Composition(Representation.MassFraction, html, elms, vals, vars);
   }

   public static Composition massFraction(final String html, final List<Element> elms, final RealVector vals, final RealMatrix cov) {
      assert elms.size() == vals.getDimension();
      assert elms.size() == cov.getRowDimension();
      assert elms.size() == cov.getColumnDimension();
      return new Composition(Representation.MassFraction, html, elms, vals, cov);
   }

   /**
    * Parses basic strings in common chemical formula notation. It handles
    * simple formula like H2SO4 and more complex ones like Ca5(PO4)3F.
    * Capitalization is critical to differentiate PO4 from Po4 and other
    * niceties.
    *
    * @param str
    * @return
    * @throws ParseException
    */
   public static Composition parse(final String str)
         throws ParseException {
      return new Composition(Representation.Stoichiometry, htmlHelper(str), parseHelper(str));
   }

   public static Composition atomFraction(final String html, final List<Element> elms, final RealVector vals, final RealVector vars) {
      assert elms.size() == vals.getDimension();
      assert elms.size() == vars.getDimension();

      return new Composition(Representation.AtomFraction, html, elms, vals, vars);
   }

   public static Composition atomFraction(final String html, final List<Element> elms, final RealVector vals, final RealMatrix cov) {
      assert elms.size() == vals.getDimension();
      assert elms.size() == cov.getRowDimension();
      assert elms.size() == cov.getColumnDimension();
      return new Composition(Representation.AtomFraction, html, elms, vals, cov);
   }

   public static Composition atomFraction(final String html, final Map<Element, ? extends Number> men) {
      final List<Element> elms = new ArrayList<>(men.keySet());
      final RealVector rv = new ArrayRealVector(elms.size());
      final RealVector var = new ArrayRealVector(elms.size());
      for(int i = 0; i < elms.size(); ++i) {
         final Number n = men.get(elms.get(i));
         rv.setEntry(i, n.doubleValue());
         if(n instanceof UncertainValue)
            var.setEntry(i, ((UncertainValue) n).variance());
      }
      return new Composition(Representation.AtomFraction, html, elms, rv, var);
   }

   /**
    * @param elm Element
    * @param purity [0.0,1.0]
    * @return An Composition in MassFraction representation.
    */
   public static Composition pureElement(final Element elm, final double purity) {
      final Map<Element, Number> men = new TreeMap<>();
      men.put(elm, new UncertainValue(purity, 1.0 - purity));
      return massFraction("Pure " + elm.getAbbrev(), men);
   }

   /**
    * @param elm Element
    * @param purity [0.0,1.0]
    * @return An Composition in MassFraction representation.
    */
   public static Composition pureElement(final Element elm) {
      final Map<Element, Number> men = new TreeMap<>();
      men.put(elm, Double.valueOf(1.0));
      return massFraction("Pure " + elm.getAbbrev(), men);
   }

   public static Composition massFraction(final String html, final Map<Element, ? extends Number> men) {
      final List<Element> elms = new ArrayList<>(men.keySet());
      final RealVector rv = new ArrayRealVector(elms.size());
      final RealVector var = new ArrayRealVector(elms.size());
      for(int i = 0; i < elms.size(); ++i) {
         final Number n = men.get(elms.get(i));
         rv.setEntry(i, n.doubleValue());
         if(n instanceof UncertainValue)
            var.setEntry(i, ((UncertainValue) n).variance());
      }
      return new Composition(Representation.MassFraction, html, elms, rv, var);
   }

   private static String htmlHelper(final String str) {
      final StringBuffer res = new StringBuffer();
      final int len = str.length();
      boolean inElm = false, inNum = false;
      for(int i = 0; i < len; ++i) {
         final char c = str.charAt(i);
         if(Character.isDigit(c)) {
            inNum = true;
            if(inElm) {
               res.append("<sub>");
               inElm = false;
            }
            res.append(c);
         } else {
            inElm = true;
            if(inNum) {
               res.append("</sub>");
               inNum = false;
            }
            res.append(c);
         }
      }
      if(inNum)
         res.append("</sub>");
      return res.toString();
   }

   private static HashMap<Element, Integer> parseHelper(final String formula)
         throws ParseException {
      int cx = 0;
      final HashMap<Element, Integer> res = new HashMap<>();
      int next = 0;
      for(int i = 0; i < formula.length(); i = next) {
         next = i + 1;
         int mult = 1;
         final char c0 = formula.charAt(i);
         if(Character.isWhitespace(c0))
            break;
         if(c0 == '(') {
            ++cx;
            for(int j = next; j < formula.length(); ++j) {
               if(formula.charAt(j) == '(')
                  ++cx;
               else if(formula.charAt(j) == ')')
                  --cx;
               if(cx == 0) {
                  next = j + 1;
                  for(int k = next; k < formula.length(); ++k)
                     if(Character.isDigit(formula.charAt(k))) {
                        mult = Integer.parseInt(formula.substring(next, k + 1));
                        next = k + 1;
                        break;
                     }
                  // Parse inside of (...)
                  final HashMap<Element, Integer> tmp = parseHelper(formula.substring(i + 1, j).trim());
                  for(final Map.Entry<Element, Integer> me : tmp.entrySet()) {
                     final int prev = res.containsKey(me.getKey()) ? res.get(me.getKey()) : 0;
                     res.put(me.getKey(), Integer.valueOf(prev + (me.getValue() * mult)));
                  }
                  break;
               }
            }
         } else // Look for element abbreviaton (1 or 2 characters)
         if((c0 >= 'A') && (c0 <= 'Z')) {
            if(next < formula.length()) {
               // Peak ahead
               final char c1 = formula.charAt(next);
               if((c1 >= 'a') && (c1 <= 'z'))
                  next++;
            }
            final Element elm = Element.parse(formula.substring(i, next));
            if(elm == null)
               throw new ParseException(formula, i);
            // Look for multiplier
            int mult2 = 0;
            if((next < formula.length()) && Character.isDigit(formula.charAt(next))) {
               for(int k = next; k < formula.length(); ++k)
                  if(Character.isDigit(formula.charAt(k))) {
                     mult2 = (10 * mult2) + Integer.parseInt(formula.substring(next, k + 1));
                     next = k + 1;
                  } else
                     break;
            } else
               mult2 = 1;
            if(mult2 > 99)
               throw new ParseException(formula, next);
            final int prev = res.containsKey(elm) ? res.get(elm) : 0;
            res.put(elm, Integer.valueOf(prev + mult2));
         } else
            throw new ParseException(formula, i);
      }
      return res;
   }

   @Override
   public int hashCode() {
      return Objects.hashCode(mHTML);
   }

   @Override
   public boolean equals(final Object obj) {
      if(this == obj)
         return true;
      if(obj == null)
         return false;
      if(getClass() != obj.getClass())
         return false;
      final Composition other = (Composition) obj;
      return Objects.equal(mHTML, other.mHTML) && super.equals(other);
   }

   public Set<Element> getElementSet() {
      final TreeSet<Element> res = new TreeSet<>();
      for(final Object obj : getTags()) {
         assert obj instanceof ElementTag;
         res.add(((ElementTag) obj).getElement());
      }
      return res;
   }

   private List<Element> getElementList() {
      final List<Element> res = new ArrayList<>();
      for(final Object obj : getTags()) {
         assert obj instanceof ElementTag;
         res.add(((ElementTag) obj).getElement());
      }
      return res;
   }

   public UncertainValue getValue(final Element elm) {
      for(final Object obj : getTags()) {
         assert obj instanceof ElementTag;
         if(((ElementTag) obj).getElement() == elm)
            return super.getValue(obj);
      }
      return UncertainValue.ZERO;
   }

   public double getCovariance(final Element elm1, final Element elm2) {
      int p1 = -1, p2 = -1;
      final List<Object> tags = getTags();
      for(int i = 0; i < tags.size(); ++i) {
         final Object obj = tags.get(i);
         assert obj instanceof ElementTag;
         final Element elm = ((ElementTag) obj).getElement();
         if(elm == elm1)
            p1 = i;
         if(elm == elm2)
            p2 = i;
      }
      return (p1 != -1) && (p2 != -1) ? getCovariance(p1, p2) : 0.0;
   }

   public static class AtomFractionToMassFraction
      extends
      NamedMultivariateJacobianFunction {

      /**
       * Nominally the tabulated atomic weights but can be customized for
       * special situations.
       */
      private final RealVector mAtomicWeights;

      private static RealVector buildAtomicWeights(final List<? extends Object> elms) {
         final RealVector atomicWeights = new ArrayRealVector(elms.size());
         for(int i = 0; i < elms.size(); ++i)
            atomicWeights.setEntry(i, ((ElementTag) elms.get(i)).getElement().getAtomicWeight());
         return atomicWeights;
      }

      /**
       * Constructs a AtomicFractionToMassFraction
       *
       * @param inputTags
       * @param outputTags
       */
      public AtomFractionToMassFraction(final Composition comp) {
         this(comp, buildAtomicWeights(comp.getTags()));
      }

      public AtomFractionToMassFraction(final Composition comp, final RealVector atomicWeights) {
         super(comp.getTags(), buildTags(comp, Representation.MassFraction));
         mAtomicWeights = new ArrayRealVector(atomicWeights);
      }

      private double massFraction(final int elmIdx, final RealVector point) {
         return point.getEntry(elmIdx) * mAtomicWeights.getEntry(elmIdx) / (point.dotProduct(mAtomicWeights));
      }

      private double deriv(final int idx1, final int idx2, final RealVector point) {
         final double den = point.dotProduct(mAtomicWeights);
         final double a1 = mAtomicWeights.getEntry(idx1);
         final double a2 = mAtomicWeights.getEntry(idx2);
         if(idx1 == idx2)
            return a1 * (1.0 / den - a1 * point.getEntry(idx1) / (den * den));
         else
            return -point.getEntry(idx1) * a1 * a2 / Math.pow(den, 2.0);
      }

      @Override
      public Pair<RealVector, RealMatrix> value(final RealVector point) {
         if(point.getDimension() != getInputDimension())
            throw new DimensionMismatchException(point.getDimension(), getInputDimension());
         final RealVector vals = new ArrayRealVector(point.getDimension());
         final RealMatrix jac = new Array2DRowRealMatrix(point.getDimension(), point.getDimension());
         final List<? extends Object> elms = getInputTags();
         for(int i = 0; i < elms.size(); ++i) {
            vals.setEntry(i, massFraction(i, point));
            for(int j = 0; j < elms.size(); ++j)
               jac.setEntry(i, j, deriv(i, j, point));
         }
         return Pair.create(vals, jac);
      }

   }

   public static class MassFractionToAtomFraction
      extends
      NamedMultivariateJacobianFunction {

      /**
       * Nominally the tabulated atomic weights but can be customized for
       * special situations.
       */
      private final RealVector mInvAtomicWeights;

      private static RealVector buildAtomicWeights(final List<? extends Object> elms) {
         final RealVector atomicWeights = new ArrayRealVector(elms.size());
         for(int i = 0; i < elms.size(); ++i)
            atomicWeights.setEntry(i, 1.0 / ((ElementTag) elms.get(i)).getElement().getAtomicWeight());
         return atomicWeights;
      }

      /**
       * Constructs a AtomicFractionToMassFraction
       *
       * @param inputTags
       * @param outputTags
       */
      public MassFractionToAtomFraction(final List<? extends Object> inputTags, final List<? extends Object> outTags) {
         this(inputTags, outTags, buildAtomicWeights(inputTags));
      }

      public MassFractionToAtomFraction(final List<? extends Object> inTags, final List<? extends Object> outTags, final RealVector atomicWeights) {
         super(inTags, outTags);
         mInvAtomicWeights = new ArrayRealVector(atomicWeights);
      }

      private double atomFraction(final int elmIdx, final RealVector point) {
         return point.getEntry(elmIdx) * mInvAtomicWeights.getEntry(elmIdx) / (point.dotProduct(mInvAtomicWeights));
      }

      private double deriv(final int idx1, final int idx2, final RealVector point) {
         final double den = point.dotProduct(mInvAtomicWeights);
         final double a1 = mInvAtomicWeights.getEntry(idx1);
         final double a2 = mInvAtomicWeights.getEntry(idx2);
         if(idx1 == idx2)
            return a1 * (1.0 / den - a1 * point.getEntry(idx1) / (den * den));
         else
            return -point.getEntry(idx1) * a1 * a2 / Math.pow(den, 2.0);
      }

      @Override
      public Pair<RealVector, RealMatrix> value(final RealVector point) {
         if(point.getDimension() != getInputDimension())
            throw new DimensionMismatchException(point.getDimension(), getInputDimension());
         final RealVector vals = new ArrayRealVector(point.getDimension());
         final RealMatrix jac = new Array2DRowRealMatrix(point.getDimension(), point.getDimension());
         final List<? extends Object> elms = getInputTags();
         for(int i = 0; i < elms.size(); ++i) {
            vals.setEntry(i, atomFraction(i, point));
            for(int j = 0; j < elms.size(); ++j)
               jac.setEntry(i, j, deriv(i, j, point));
         }
         return Pair.create(vals, jac);
      }

   }

   /**
    * Converts (as necessary) from the internal representation into an object
    * with the Representation.MassFraction. The original object is returned for
    * MassFraction. For NormalizedMassFraction, the values are unchanged but the
    * {@link NormalizedMassFractionTag}s are replaced with
    * {@link MassFractionTag}s.
    *
    * @return Composition in Representation.MassFraction
    */
   public Composition asMassFraction() {
      final List<Element> elms = getElementList();
      switch(mRepresentation) {
         case MassFraction:
            return this;
         case NormalizedMassFraction:
            return new Composition(Representation.MassFraction, mHTML, getElementList(), getValues(), getCovariances());
         case Stoichiometry:
         case AtomFraction: {
            final UncertainValues mf = UncertainValues.propagate(new AtomFractionToMassFraction(this), this);
            return new Composition(Representation.MassFraction, mHTML, elms, mf.getValues(), mf.getCovariances());
         }
         default:
            assert false;
            return null;
      }
   }

   /**
    * Converts (as necessary) from the internal representation into an object
    * with the Representation.NormalizedMassFraction. The original object is
    * returned for NormalizedMassFraction.
    *
    * @return Composition in Representation.NormalizedMassFraction
    */
   public Composition asNormalizedMassFraction() {
      final List<Element> elms = getElementList();
      switch(mRepresentation) {
         case MassFraction: {
            final UncertainValues mf = UncertainValues.propagate(NamedMultivariateJacobianFunction.normalize(getTags(), buildTags(mHTML, elms, Representation.NormalizedMassFraction)), this);
            return new Composition(Representation.NormalizedMassFraction, mHTML, elms, mf.getValues(), mf.getCovariances());
         }
         case NormalizedMassFraction:
            return this;
         case AtomFraction:
         case Stoichiometry: {
            final UncertainValues mf = UncertainValues.propagate(new AtomFractionToMassFraction(this), this);
            return new Composition(Representation.NormalizedMassFraction, mHTML, elms, mf.getValues(), mf.getCovariances());
         }
         default:
            assert false;
            return null;
      }
   }

   /**
    * Converts (as necessary) from the internal representation into an object
    * with the Representation.AtomFraction. The original object is returned for
    * objects already in AtomFraction representation.
    *
    * @return Composition in Representation.AtomFraction
    */
   public Composition asAtomFraction() {

      final List<Element> elms = getElementList();
      switch(mRepresentation) {
         case NormalizedMassFraction:
         case MassFraction: {
            final UncertainValues mf = UncertainValues.propagate(new MassFractionToAtomFraction(getTags(), buildTags(mHTML, elms, Representation.NormalizedMassFraction)), this);
            return new Composition(Representation.AtomFraction, mHTML, elms, mf.getValues(), mf.getCovariances());
         }
         case AtomFraction:
            return this;
         case Stoichiometry: {
            final UncertainValues mf = UncertainValues.propagate(NamedMultivariateJacobianFunction.normalize(getTags(), buildTags(mHTML, elms, Representation.AtomFraction)), this);
            return new Composition(Representation.AtomFraction, mHTML, elms, mf.getValues(), mf.getCovariances());
         }
         default:
            assert false;
            return null;
      }
   }

   public Representation getNativeRepresentation() {
      return mRepresentation;
   }

   public static class TotalTag
      extends
      BaseTag {

      private TotalTag(final Composition comp) {
         super("Total", comp);
      }

      public Composition getComposition() {
         return (Composition) getObject1();
      }
   }

   public static Object buildTotalTag(final Composition comp) {
      return new TotalTag(comp);
   }

   public UncertainValues getAnalyticalTotal() {
      return UncertainValues.propagate(NamedMultivariateJacobianFunction.sum(getTags(), new TotalTag(this)), this);
   }

   public static class MeanZTag
      extends
      BaseTag {

      public MeanZTag(final Composition comp) {
         super("MeanZ", comp);
      }

      public Composition getComposition() {
         return (Composition) getObject1();
      }
   }

   public static Object buildMeanZTag(final Composition comp) {
      return new MeanZTag(comp);
   }

   public UncertainValues getMeanAtomicNumber() {
      final List<? extends Object> tags = getTags();
      final double[] z = new double[tags.size()];
      for(int i = 0; i < z.length; ++i)
         z[i] = ((ElementTag) tags.get(i)).getElement().getAtomicNumber();
      return UncertainValues.propagate(NamedMultivariateJacobianFunction.linear(tags, MatrixUtils.createRealVector(z), buildMeanZTag(this)), this);
   }

   public static List<Composition> toMassFraction(final List<Composition> comps) {
      final List<Composition> res = new ArrayList<>();
      for(final Composition comp : comps)
         res.add(comp.asMassFraction());
      return res;
   }

   /**
    * @see gov.nist.microanalysis.roentgen.html.IToHTML#toHTML(gov.nist.microanalysis.roentgen.html.IToHTML.Mode)
    */
   @Override
   public String toHTML(final Mode mode) {
      switch(mRepresentation) {
         case MassFraction:
         case NormalizedMassFraction:
            return toHTMLasMassFraction(mode);
         case AtomFraction:
            return toHTMLasAtomFraction(mode);
         case Stoichiometry:
            return toHTMLasStoichiometry(mode);
         default:
            assert false;
            return "ERROR";
      }
   }

   private String toHTMLasStoichiometry(final Mode mode) {
      assert (mRepresentation == Representation.Stoichiometry);
      switch(mode) {
         case TERSE:
         case NORMAL:
         default:
            return mHTML;
         case VERBOSE: {
            final Table t = new Table();
            final Composition mf = asMassFraction();
            t.addRow(Table.th("Element"), Table.thc("Z"), Table.thc("Atoms"), Table.thc("Mass<br/>Fraction"));
            final BasicNumberFormat nf = MASS_FRACTION_FORMAT;
            for(final Object tag : getTags()) {
               final ElementTag elementTag = (ElementTag) tag;
               final Element elm = elementTag.getElement();
               final UncertainValue uv = getValue(elementTag);
               t.addRow(Table.td(elm.getAbbrev()), //
                     Table.tdc(Integer.toString(elm.getAtomicNumber())), //
                     Table.tdc(uv.doubleValue()), //
                     Table.tdc(nf.format(mf.getValue(elm))));
            }
            return HTML.subHeader(mHTML) + t.toHTML(Mode.VERBOSE);
         }
      }
   }

   private String toHTMLasAtomFraction(final Mode mode) {
      assert (mRepresentation == Representation.AtomFraction);
      switch(mode) {
         case TERSE:
            return mHTML;
         case NORMAL: {
            final Table t = new Table();
            final List<Item> hdr = new ArrayList<>();
            final Composition mf = asMassFraction();
            hdr.add(Table.th("Element"));
            hdr.add(Table.thc("Z"));
            hdr.add(Table.thc("Mass<br/>Fraction"));
            hdr.add(Table.thc("Uncertainty<br/>(1 &sigma;)"));
            hdr.add(Table.thc("Atomic<br/>Fraction"));
            hdr.add(Table.thc("Uncertainty<br/>(1 &sigma;)"));
            t.addRow(hdr);
            for(final Element elm : getElementSet()) {
               final List<Item> row = new ArrayList<>();
               row.add(Table.td(elm.getAbbrev()));
               row.add(Table.tdc(Integer.toString(elm.getAtomicNumber())));
               final UncertainValue mfVal = mf.getValue(elm);
               row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(mfVal.doubleValue())));
               row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(mfVal.uncertainty())));
               final UncertainValue afVal = getValue(elm);
               row.add(Table.tdc(ATOMIC_FRACTION_FORMAT.formatHTML(afVal.doubleValue())));
               row.add(Table.tdc(ATOMIC_FRACTION_FORMAT.formatHTML(afVal.uncertainty())));
               t.addRow(row);
            }
            return t.toHTML(Mode.NORMAL);
         }
         default:
            return toHTML(Mode.VERBOSE, ATOMIC_FRACTION_FORMAT_LONG);
      }
   }

   private static final class FracTag
      extends
      BaseTag {

      protected FracTag(final Composition comp) {
         super("f", comp);
      }

      public Composition getComposition() {
         return (Composition) getObject1();
      }
   }

   private static final class CombineMassFractions
      extends
      NamedMultivariateJacobianFunction {

      private final String mHTML;

      static List<Object> buildOutput(final String htmlName, final List<Element> elms) {
         final List<Object> outTags = new ArrayList<>();
         for(final Element elm : elms)
            outTags.add(new MassFractionTag(htmlName, elm));
         return outTags;
      }

      public CombineMassFractions(final String htmlName, final List<? extends Object> inputTags, final List<Element> elms) {
         super(inputTags, buildOutput(htmlName, elms));
         mHTML = htmlName;
      }

      @Override
      public Pair<RealVector, RealMatrix> value(final RealVector point) {
         final List<? extends Object> inTags = getInputTags();
         final RealMatrix rm = MatrixUtils.createRealMatrix(getOutputDimension(), getInputDimension());
         final RealVector rv = new ArrayRealVector(getOutputDimension());
         for(final Object inTag : inTags) {
            if(inTag instanceof FracTag) {
               final FracTag ct = (FracTag) inTag;
               final int fIdx = inputIndex(inTag);
               final String c = "<html>" + ct.getComposition().mHTML;
               for(final Object inTag2 : inTags) {
                  if((inTag2 instanceof MassFractionTag) && (((MassFractionTag) inTag2).getHTML().equals(c))) {
                     final MassFractionTag mt2 = (MassFractionTag) inTag2;
                     final int mfIdx = inputIndex(mt2);
                     final MassFractionTag outTag = new MassFractionTag(mHTML, mt2.getElement());
                     final int outIdx = outputIndex(outTag);
                     rv.setEntry(outIdx, rv.getEntry(outIdx) + point.getEntry(fIdx) * point.getEntry(mfIdx));
                     rm.setEntry(outIdx, mfIdx, point.getEntry(fIdx));
                     rm.setEntry(outIdx, fIdx, point.getEntry(mfIdx));
                  }
               }
            }
         }
         return Pair.create(rv, rm);
      }
   }

   /**
    * Build a new MassFraction representation of a mixture of the specified
    * compositions.
    *
    * @param htmlName A name for the new material
    * @param comps A Pair&lt;Composition, Number&gt; with the composition and
    *           fractional quantity.
    * @return Composition in MassFraction Representation.
    * @throws ArgumentException
    */
   @SafeVarargs
   public static Composition combine(final String htmlName, final Pair<Composition, Number>... comps)
         throws ArgumentException {
      final List<Object> tags = new ArrayList<>();
      final HashMap<Composition, Number> mfs = new HashMap<>();
      for(final Pair<Composition, Number> me : comps) {
         final Composition mf = me.getFirst().asMassFraction();
         mfs.put(mf, me.getSecond());
         tags.add(new FracTag(mf));
      }
      final UncertainValues fracs = new UncertainValues(new ArrayList<>(tags));
      for(final Object tag : tags) {
         final Number n = mfs.get(((FracTag) tag).getComposition());
         fracs.set(tag, n.doubleValue(), n instanceof UncertainValue ? ((UncertainValue) n).variance() : 0.0);
      }
      final UncertainValues[] uvs = new UncertainValues[mfs.size() + 1];
      final List<Element> elms = new ArrayList<>();
      uvs[0] = fracs;
      int i = 1;
      for(final Map.Entry<Composition, Number> me : mfs.entrySet()) {
         final Composition comp = me.getKey();
         uvs[i] = comp;
         for(final Object ctag : me.getKey().getTags()) {
            assert ctag instanceof MassFractionTag;
            final MassFractionTag mft = (MassFractionTag) ctag;
            if(!elms.contains(mft.getElement()))
               elms.add(mft.getElement());
            tags.add(ctag);
         }
         ++i;
      }
      final CombineMassFractions cmf = new CombineMassFractions(htmlName, tags, elms);
      final UncertainValues tmp = UncertainValues.propagate(cmf, UncertainValues.build(tags, uvs));
      return Composition.massFraction(htmlName, elms, tmp.getValues(), tmp.getCovariances());
   }

   private String toHTMLasMassFraction(final Mode mode) {
      assert (mRepresentation == Representation.MassFraction) || (mRepresentation == Representation.NormalizedMassFraction);
      switch(mode) {
         case TERSE:
            return mHTML;
         case NORMAL: {
            final Table t = new Table();
            try {
               final Composition nmf = asNormalizedMassFraction();
               final Composition af = asAtomFraction();
               t.addRow(Table.th(mHTML, 8));
               t.addRow(Table.th("Element"), //
                     Table.thc("Z"), //
                     Table.thc("Mass<br/>Fraction"),//
                     Table.thc("Uncertainty<br/>(1 &sigma;)"),//
                     Table.thc("Normalized<br/>Mass Fraction"),//
                     Table.thc("Uncertainty<br/>(1 &sigma;)"),//
                     Table.thc("Atomic<br/>Fraction"),//
                     Table.thc("Uncertainty<br/>(1 &sigma;)"));//
               for(final Element elm : getElementSet()) {
                  final List<Item> row = new ArrayList<>();
                  row.add(Table.td(elm.getAbbrev()));
                  row.add(Table.tdc(Integer.toString(elm.getAtomicNumber())));
                  final UncertainValue mfVal = getValue(elm);
                  row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(mfVal.doubleValue())));
                  row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(mfVal.uncertainty())));
                  final UncertainValue nmfVal = nmf.getValue(elm);
                  row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(nmfVal.doubleValue())));
                  row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(nmfVal.uncertainty())));
                  final UncertainValue afVal = af.getValue(elm);
                  row.add(Table.tdc(ATOMIC_FRACTION_FORMAT.formatHTML(afVal.doubleValue())));
                  row.add(Table.tdc(ATOMIC_FRACTION_FORMAT.formatHTML(afVal.uncertainty())));
                  t.addRow(row);
               }
               final List<Item> row = new ArrayList<>();
               row.add(Table.td("Total")); // Element
               final UncertainValues meanZ = getMeanAtomicNumber();
               row.add(Table.tdc(MEAN_Z_FORMAT.formatHTML(meanZ.getEntry(buildMeanZTag(this))))); // Z
               final UncertainValues total = getAnalyticalTotal();
               final UncertainValue sum = total.getValue(buildTotalTag(this));
               row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(sum.doubleValue()))); // MassFrac
               row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(sum.uncertainty()))); // Unc
               final UncertainValues nTotal = nmf.getAnalyticalTotal();
               final UncertainValue nsum = nTotal.getValue(buildTotalTag(nmf));
               row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(nsum.doubleValue()))); // MassFrac
               row.add(Table.tdc(MASS_FRACTION_FORMAT.formatHTML(nsum.uncertainty()))); // Unc
               row.add(Table.td()); // Atomic
               row.add(Table.td()); // Unc
               t.addRow(row);
            }
            catch(final Exception e) {
               e.printStackTrace();
            }
            return t.toHTML(Mode.NORMAL);
         }
         default:
            return toHTML(Mode.VERBOSE, MASS_FRACTION_FORMAT);
      }
   }

   /**
    * Does this material contain the specified element?
    *
    * @param element
    * @return true if the quantity associated with the specified element is
    *         non-zero.
    */
   public boolean contains(final Element element) {
      for(final Object obj : getTags())
         if(((ElementTag) obj).getElement() == element)
            return true;
      return false;
   }

   @Override
   public String toString() {
      return HTML.stripTags(mHTML);
   }

}