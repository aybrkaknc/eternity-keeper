/**
 * Eternity Keeper, a Pillars of Eternity save game editor.
 * Copyright (C) 2015 the authors.
 * <p>
 * Eternity Keeper is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * Eternity Keeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.me.mantas.eternity;

import org.slf4j.LoggerFactory;
import java.io.PrintWriter;
import java.io.StringWriter;

public class Logger {
	private final Class cls;
	private final org.slf4j.Logger slfLogger;

	private Logger(final Class cls) {
		this.cls = cls;
		slfLogger = LoggerFactory.getLogger(cls);
	}

	public void info(final String format, final Object... varargs) {
		log("INFO   ", null, format, varargs);
	}

	public void warn(final String format, final Object... varargs) {
		log("WARN   ", null, format, varargs);
	}

	public void error(final String format, final Object... varargs) {
		log("ERROR  ", null, format, varargs);
	}

	public void error(final Throwable t, final String format, final Object... varargs) {
		log("ERROR  ", t, format, varargs);
	}

	private void log(final String level, final Throwable t, final String format, final Object... varargs) {
		final String className = cls.getSimpleName();
		final int lineNumber = t != null ? getExceptionLineNumber(t) : getCallerLineNumber();

		String displayClassName = String.format("%-20s", className);
		if (displayClassName.length() > 20) {
			displayClassName = displayClassName.substring(0, 20);
		}

		final String displayLineNumber = String.format("%-4d", lineNumber);
		final String message = String.format(format, varargs);

		StringBuilder sb = new StringBuilder();
		sb.append(displayClassName).append(" ").append(displayLineNumber).append(" ").append(level).append(" ")
				.append(message);

		if (t != null) {
			sb.append("\nException Details: ").append(t.toString());
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			sb.append("\nStack Trace:\n").append(sw.toString());
		}

		final String fullMessage = sb.toString();

		switch (level.trim()) {
			case "INFO":
				slfLogger.info(fullMessage);
				break;
			case "WARN":
				slfLogger.warn(fullMessage);
				break;
			case "ERROR":
				slfLogger.error(fullMessage);
				break;
			default:
				slfLogger.error(fullMessage);
		}
	}

	private int getCallerLineNumber() {
		final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		for (int i = 1; i < stackTrace.length; i++) {
			if (!stackTrace[i].getClassName().equals(Logger.class.getName()) &&
					!stackTrace[i].getMethodName().equals("log")) {
				return stackTrace[i].getLineNumber();
			}
		}
		return 0;
	}

	private int getExceptionLineNumber(Throwable t) {
		StackTraceElement[] st = t.getStackTrace();
		if (st != null && st.length > 0) {
			return st[0].getLineNumber();
		}
		return 0;
	}

	public static Logger getLogger(final Class cls) {
		return new Logger(cls);
	}
}
