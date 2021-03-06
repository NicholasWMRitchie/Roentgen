package com.duckandcover.html;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.duckandcover.html.IToHTML.Mode;

/**
 * <p>
 * Utility functions to facilitate working with HTML.
 * </p>
 * <p>
 * Copyright Nicholas W. M. Ritchie 2014-2017
 * </p>
 *
 * @author Nicholas W. M. Ritchie
 * @version $Rev: 303 $
 */
public class Transforms {

	final static String MATCHES_SCIENTIFIC_NOTATION = "^[-+]?[0-9]*\\.?[0-9]+[eE][-+]?[0-9]+$";

	final public static String NON_BREAKING_DASH = "&#8209;";

	/**
	 * Replaces the e|E in #.####e### with #.###&middot;10<sup>###</sup> and
	 * replaces the '-' with a non-breaking hypen.
	 *
	 * @param number String
	 * @return Number in HTML formatted string
	 */
	public static String numberToHTML(final String number) {
		// #.####e### -> #.###&middot;10<sup>###</sup>
		if (number.matches(MATCHES_SCIENTIFIC_NOTATION)) {
			final String uc = number.toUpperCase().replace("-", NON_BREAKING_DASH);
			final int p = uc.indexOf("E");
			if (uc.substring(p + 1).equals("0"))
				return uc.substring(0, p);
			else
				return uc.replace("E", "&sdot;10<sup>").concat("</sup>");
		} else
			return number.replace("-", NON_BREAKING_DASH);
	}

	private static class HTMLTransform implements IToHTML, IToHTMLExt {
		private final IToHTML mContent;
		private final String mOpening;
		private final String mClosing;

		private HTMLTransform(final IToHTML content, final String opening, final String closing) {
			mContent = content;
			mOpening = opening;
			mClosing = closing;
		}

		private String wrap(final String content) {
			return mOpening + content + mClosing;
		}

		@Override
		public String toHTML(final Mode mode) {
			return wrap(mContent.toHTML(mode));
		}

		@Override
		public String toHTML(final Mode mode, final File base, final String dir) throws IOException {
			if (mContent instanceof IToHTMLExt)
				return wrap(((IToHTMLExt) mContent).toHTML(mode, base, dir));
			else
				return wrap(mContent.toHTML(mode));
		}
	}

	public static IToHTML h3(final IToHTML content) {
		return new HTMLTransform(content, "<h3>", "</h3>");
	}

	public static IToHTML h4(final IToHTML content) {
		return new HTMLTransform(content, "<h4>", "</h4>");
	}

	public static IToHTML b(final IToHTML content) {
		return new HTMLTransform(content, "<b>", "</b>");
	}

	public static IToHTML i(final IToHTML content) {
		return new HTMLTransform(content, "<i>", "</i>");
	}

	public static IToHTML scrollPane(final IToHTML content) {
		return new HTMLTransform(content, "<div style=\"overflow-x:auto;\">", "</div>");
	}

	public static IToHTML transform(final IToHTML item, final String opening, final String closing) {
		return new HTMLTransform(item, opening, closing);
	}

	public static IToHTML createHTML(final String html) {
		class HTML implements IToHTML {
			private final String mHTML;

			HTML(final String html) {
				mHTML = html.startsWith("<html>") ? html.substring(6) : html;
			}

			@Override
			public String toHTML(final Mode mode) {
				return mHTML;
			}
		}
		return new HTML(html);
	}
	
	public static IToHTML verbatim(final String text) {
		return verbatim(text, "black");
	}

	public static IToHTML verbatim(final String text, String color) {
		class Verbatim implements IToHTML {
			private final String mHTML;

			Verbatim(final String text, final String color) {
				String verb = text.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>");
				mHTML = "<pre style=\"background-color:LightGray;color:" + color + "\">" + verb + "</pre>";
			}

			@Override
			public String toHTML(final Mode mode) {
				return mHTML;
			}
		}
		return new Verbatim(text, color);
	}

	public static IToHTML createParagraph(final IToHTML content) {
		return new HTMLTransform(content, "<p>", "</p>");
	}

	public static IToHTML createList(final List<? extends Object> items) {
		class HTMLList implements IToHTML {
			private final List<Object> mItems;

			private HTMLList(final List<? extends Object> items) {
				mItems = new ArrayList<>(items);
			}

			@Override
			public String toHTML(final Mode mode) {
				final StringBuffer sb = new StringBuffer();
				if (mode == IToHTML.Mode.TERSE)
					sb.append("<p>List containing " + mItems.size() + " items.</p>");
				else {
					sb.append("<ul>");
					for (final Object item : mItems) {
						sb.append("<li>");
						if (item instanceof IToHTML)
							sb.append(((IToHTML) item)
									.toHTML(mode == IToHTML.Mode.NORMAL ? IToHTML.Mode.TERSE : IToHTML.Mode.NORMAL));
						else
							sb.append(HTML.escape(item.toString()));
						sb.append("</li>");
					}
					sb.append("</ul>");

				}
				return sb.toString();
			}
		}
		return new HTMLList(items);
	}

	/**
	 * Demotes a IToHTMLExt object to a IToHTML object.
	 *
	 * @param html {@link IToHTMLExt}
	 * @return {@link IToHTML}L
	 */
	public static IToHTML demote(final IToHTML html) {
		class Forced implements IToHTML {
			private final IToHTML mExt;

			Forced(final IToHTML html) {
				mExt = html;
			}

			@Override
			public String toHTML(final Mode mode) {
				return mExt.toHTML(mode);
			}
		}
		return new Forced(html);
	}

	/**
	 * Demotes a IToHTMLExt object to a IToHTML object.
	 *
	 * @param html {@link IToHTMLExt}
	 * @return {@link IToHTML}L
	 */
	public static IToHTMLExt promote(final IToHTML html) {
		class Promoted implements IToHTMLExt {
			private final IToHTML mExt;

			Promoted(final IToHTML html) {
				mExt = html;
			}

			@Override
			public String toHTML(final Mode mode) {
				return mExt.toHTML(mode);
			}

			@Override
			public String toHTML(final Mode mode, final File base, final String dir) throws IOException {
				if (mExt instanceof IToHTMLExt)
					return ((IToHTMLExt) mExt).toHTML(mode, base, dir);
				else
					return mExt.toHTML(mode);
			}

		}
		return new Promoted(html);
	}

	/**
	 * Forces the IToHTML or IToHTMLExt object to display using a specified Modes.
	 *
	 * @param html {@link IToHTMLExt}
	 * @param mode {@link com.duckandcover.roentgen.html.IToHTML.Mode}
	 * @return {@link IToHTML}L
	 */
	public static IToHTML forceMode(final IToHTML html, final Mode mode) {
		class Forced implements IToHTMLExt {
			private final IToHTML mExt;
			private final Mode mMode;

			Forced(final IToHTML html, final Mode mode) {
				mExt = html;
				mMode = mode;
			}

			@Override
			public String toHTML(final Mode mode) {
				return mExt.toHTML(mMode);
			}

			@Override
			public String toHTML(final Mode mode, final File base, final String dir) throws IOException {
				return mExt instanceof IToHTMLExt ? ((IToHTMLExt) mExt).toHTML(mMode, base, dir) : mExt.toHTML(mMode);
			}
		}
		return new Forced(html, mode);
	}

}