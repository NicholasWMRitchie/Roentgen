package gov.nist.microanalysis.roentgen.utility;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

import com.duckandcover.html.HTML;
import com.duckandcover.html.Transforms;

import gov.nist.juncertainty.UncertainValue;
import gov.nist.juncertainty.UncertainValueEx;

/**
 * <p>
 * This class provides a more sophisticated mechanism for presenting numbers in
 * text or HTML. It provides a mechanism to switch from one format to another
 * dependent upon the magnitude of the number both for small and large numbers.
 * </p>
 * <p>
 * Copyright Nicholas W. M. Ritchie 2014-2019
 * </p>
 *
 * @author Nicholas W. M. Ritchie
 * @version $Rev: 303 $
 */
public class BasicNumberFormat extends DecimalFormat {

	private static final long serialVersionUID = 2397685593054167698L;
	private static final char THIN_SPACE = '\u2009';
	private static double mSmallExpThresh = 0.0;
	private static DecimalFormat mSmallExpFormat = null;
	private static double mLargeExpThresh = 0.0;
	private static DecimalFormat mLargeExpFormat = null;

	public enum OutputMode {
		/**
		 * Value only (no uncertainties)
		 */
		Value,
		/**
		 * Value plus root-mean square combined uncertainty
		 */
		ValuePlusUncertainty,
		/**
		 * Value plus all terms in the uncertainty
		 */
		ValueWithExtendedUncertainty
	};

	public BasicNumberFormat() {
		super();
		final DecimalFormatSymbols dfs = createDefaultHalfUpFormatSymbols();
		setDecimalFormatSymbols(dfs);
		setGroupingSize(3);
		setRoundingMode(RoundingMode.HALF_UP);
	}

	public static DecimalFormatSymbols createDefaultHalfUpFormatSymbols() {
		final DecimalFormatSymbols dfs = DecimalFormatSymbols.getInstance();
		dfs.setGroupingSeparator(THIN_SPACE);
		return dfs;
	}

	public BasicNumberFormat(final String fmt) {
		super(fmt, createDefaultHalfUpFormatSymbols());
		setGroupingSize(3);
		setRoundingMode(RoundingMode.HALF_UP);
	}

	public BasicNumberFormat(final String fmt, final DecimalFormatSymbols syms) {
		super(fmt, syms);
		setGroupingSize(3);
		setRoundingMode(RoundingMode.HALF_UP);
	}

	private String extFormat(final double d) {
		if (d == 0.0)
			return "0.0";
		if ((Math.abs(d) < mSmallExpThresh) && (Math.abs(d) > 1.0e-100) && (mSmallExpFormat != null))
			return mSmallExpFormat.format(d);
		if ((Math.abs(d) > mLargeExpThresh) && (mLargeExpFormat != null))
			return mLargeExpFormat.format(d);
		return format(d);
	}

	public static void setSmallNumberFormat(final double thresh, final DecimalFormat df) {
		mSmallExpThresh = thresh;
		mSmallExpFormat = df;
	}

	public static void setLargeNumberFormat(final double thresh, final DecimalFormat df) {
		mLargeExpThresh = thresh;
		mLargeExpFormat = df;
	}

	public String formatHTML(final Vector3D vec) {
		final StringBuffer sb = new StringBuffer();
		sb.append("(&thinsp;");
		sb.append(formatHTML(vec.getX()));
		sb.append(",&thinsp;");
		sb.append(formatHTML(vec.getY()));
		sb.append(",&thinsp;");
		sb.append(formatHTML(vec.getZ()));
		sb.append("&thinsp;)");
		return sb.toString();
	}

	/**
	 * Uses the SIUnits package to format Number instances in LaTeX format.
	 *
	 * @param num
	 * @return String in LaTeX format
	 */
	public String formatLaTeX(final Number num) {
		final StringBuffer res = new StringBuffer();
		res.append("\\num{");
		if (num instanceof UncertainValue) {
			res.append(format(num.doubleValue()));
			res.append("\\pm");
			res.append(format(((UncertainValue) num).uncertainty()));
		} else {
			if ((num instanceof Integer) || (num instanceof Long) || (num instanceof Byte))
				res.append(format(num.longValue()));
			else
				res.append(format(num.doubleValue()));
		}
		res.append("}");
		return res.toString().replace(Character.toString(THIN_SPACE), "");
	}

	/**
	 * Uses the SIUnits package to format a double in LaTeX format.
	 *
	 * @param num
	 * @return String in LaTeX format
	 */
	public String formatLaTeX(final double num) {
		final StringBuffer res = new StringBuffer();
		res.append("\\num{");
		res.append(format(num));
		res.append("}");
		return res.toString().replace(Character.toString(THIN_SPACE), "");
	}

	public String format(final Number num) {
		if (num instanceof UncertainValue) {
			final UncertainValue uv = (UncertainValue) num;
			final StringBuffer sb = new StringBuffer();
			sb.append(extFormat(uv.doubleValue()));
			if (uv instanceof UncertainValueEx<?>) {
				final UncertainValueEx<?> uvx = (UncertainValueEx<?>) uv;
				for (final Object comp : uvx.getComponentNames()) {
					sb.append("�");
					sb.append(extFormat(uvx.getComponent(comp)));
					sb.append("(");
					sb.append(comp);
					sb.append(")");
				}
			} else {
				sb.append("\u00B1");
				sb.append(extFormat(uv.uncertainty()));
			}

			return sb.toString();
		} else if ((num instanceof Double) || (num instanceof Float))
			return extFormat(num.doubleValue());
		else if ((num instanceof Long) || (num instanceof Integer))
			return format(num.longValue());
		return format(num);
	}

	/**
	 * Formats an UncertainValue, a Interval in special ways. Handles Double, Float
	 * and other Number objects in the normal manner.
	 *
	 * @param num  Number
	 * @param mode OutputMode One of Value, ValueWithUncertainty,
	 *             ValueWithExtendedUncertainty
	 * @return String
	 */
	public String format(final Number num, final OutputMode mode) {
		if (num instanceof UncertainValue) {
			final StringBuffer sb = new StringBuffer();
			final UncertainValue uv = (UncertainValue) num;
			switch (mode) {
			case Value:
				sb.append(extFormat(uv.doubleValue()));
				break;
			case ValuePlusUncertainty:
				sb.append(extFormat(uv.doubleValue()));
				sb.append("\u00B1");
				sb.append(extFormat(uv.uncertainty()));
				break;
			case ValueWithExtendedUncertainty:
				sb.append(extFormat(uv.doubleValue()));
				if (uv instanceof UncertainValueEx<?>) {
					final UncertainValueEx<?> uvx = (UncertainValueEx<?>) uv;
					for (final Object comp : uvx.getComponentNames()) {
						sb.append("\u00B1");
						sb.append(extFormat(uvx.getComponent(comp)));
						sb.append("(");
						sb.append(comp);
						sb.append(")");
					}
				} else {
					sb.append("\u00B1");
					sb.append(extFormat(uv.uncertainty()));
				}
				break;

			}
			return sb.toString();
		} else if ((num instanceof Double) || (num instanceof Float))
			return extFormat(num.doubleValue());
		else if ((num instanceof Long) || (num instanceof Integer))
			return format(num.longValue());
		return format(num);
	}

	public String formatHTML(final double num) {
		return Transforms.numberToHTML(HTML.escape(extFormat(num)));
	}

	/**
	 * Format a Double, Float, Integer, Long or UncertainValue in an HTML friendly
	 * way.
	 *
	 * @param num
	 * @return String in HTML
	 */
	public String formatHTML(final Number num) {
		return formatHTML(num, OutputMode.ValueWithExtendedUncertainty);
	}

	/**
	 * Format a Double, Float, Integer, Long or UncertainValue in an HTML friendly
	 * way.
	 *
	 * @param num  Number
	 * @param mode OutputMode One of Value, ValueWithUncertainty,
	 *             ValueWithExtendedUncertainty
	 * @return String in HTML
	 */
	public String formatHTML(final Number num, final OutputMode mode) {
		if (num instanceof UncertainValue) {
			final UncertainValue uv = (UncertainValue) num;
			final StringBuffer sb = new StringBuffer();
			switch (mode) {
			case Value:
				sb.append(formatHTML(uv.doubleValue()));
				break;
			case ValuePlusUncertainty:
				sb.append("(&thinsp;");
				sb.append(formatHTML(uv.doubleValue()));
				if (uv.isUncertain())
					sb.append("&thinsp;&plusmn;&thinsp;" + formatHTML(uv.uncertainty()));
				sb.append("&thinsp;)");
				break;
			case ValueWithExtendedUncertainty:
				sb.append("(&thinsp;");
				sb.append(formatHTML(uv.doubleValue()));

				sb.append(extFormat(uv.doubleValue()));
				if (uv instanceof UncertainValueEx<?>) {
					final UncertainValueEx<?> uvx = (UncertainValueEx<?>) uv;
					boolean first = true;
					for (final Object comp : uvx.getComponentNames()) {
						if (first)
							sb.append("&thinsp;&plusmn;&thinsp;(&thinsp;");
						else
							sb.append(",&thinsp;");
						sb.append(HTML.escape(comp.toString()));
						sb.append("&thinsp;:&thinsp;");
						sb.append(formatHTML(uvx.getComponent(comp)));
						first = false;
					}
					sb.append("&thinsp;)&thinsp;)");
				} else {
					sb.append("&thinsp;&plusmn;&thinsp;");
					sb.append(extFormat(uv.uncertainty()));
				}
				break;
			}
			return sb.toString();
		} else if ((num instanceof Double) || (num instanceof Float))
			return formatHTML(num.doubleValue());
		else if ((num instanceof Long) || (num instanceof Integer))
			return format(num.longValue());
		return format(num);
	}
}
