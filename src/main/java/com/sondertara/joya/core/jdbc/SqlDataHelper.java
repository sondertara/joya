/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package com.sondertara.joya.core.jdbc;

import com.sondertara.common.exception.TaraException;
import oracle.sql.TIMESTAMP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * A help for dealing with BLOB and CLOB data
 *
 * @author Steve Ebersole
 */
public final class SqlDataHelper {

  private static final Logger LOG = LoggerFactory.getLogger(SqlDataHelper.class);

  private SqlDataHelper() {}

  /** The size of the buffer we will use to deserialize larger streams */
  private static final int BUFFER_SIZE = 1024 * 4;

  public static boolean isNClob(final Class<?> type) {
    return java.sql.NClob.class.isAssignableFrom(type);
  }

  public static boolean isClob(final Class<?> type) {
    return Clob.class.isAssignableFrom(type);
  }

  public static Object extractDate(Object timestamp) {
    // convert Oracle TIMESTAMP
    if (timestamp instanceof TIMESTAMP) {
      try {
        return ((TIMESTAMP) timestamp).timestampValue(Calendar.getInstance(TimeZone.getDefault()));
      } catch (SQLException e) {
        throw new TaraException(e);
      }
    }
    return timestamp;
  }

  /**
   * Extract the contents of the given reader/stream as a string. The reader will be closed.
   *
   * @param reader The reader for the content
   * @return The content as string
   */
  public static String extractString(Reader reader) {
    return extractString(reader, BUFFER_SIZE);
  }

  /**
   * Extract the contents of the given reader/stream as a string. The reader will be closed.
   *
   * @param reader The reader for the content
   * @param lengthHint if the length is known in advance the implementation can be slightly more
   *     efficient
   * @return The content as string
   */
  public static String extractString(Reader reader, int lengthHint) {
    // read the Reader contents into a buffer and return the complete string
    final int bufferSize = getSuggestedBufferSize(lengthHint);
    final StringBuilder stringBuilder = new StringBuilder(bufferSize);
    try {
      char[] buffer = new char[bufferSize];
      while (true) {
        int amountRead = reader.read(buffer, 0, bufferSize);
        if (amountRead == -1) {
          break;
        }
        stringBuilder.append(buffer, 0, amountRead);
      }
    } catch (IOException ioe) {
      throw new TaraException("IOException occurred reading text", ioe);
    } finally {
      try {
        reader.close();
      } catch (IOException e) {
        LOG.error("", e);
      }
    }
    return stringBuilder.toString();
  }

  /**
   * Extracts a portion of the contents of the given reader/stream as a string.
   *
   * @param characterStream The reader for the content
   * @param start The start position/offset (0-based, per general stream/reader contracts).
   * @param length The amount to extract
   * @return The content as string
   */
  private static String extractString(Reader characterStream, long start, int length) {
    if (length == 0) {
      return "";
    }
    StringBuilder stringBuilder = new StringBuilder(length);
    try {
      long skipped = characterStream.skip(start);
      if (skipped != start) {
        throw new TaraException("Unable to skip needed bytes");
      }
      final int bufferSize = getSuggestedBufferSize(length);
      char[] buffer = new char[bufferSize];
      int charsRead = 0;
      while (true) {
        int amountRead = characterStream.read(buffer, 0, bufferSize);
        if (amountRead == -1) {
          break;
        }
        stringBuilder.append(buffer, 0, amountRead);
        if (amountRead < bufferSize) {
          // we have read up to the end of stream
          break;
        }
        charsRead += amountRead;
        if (charsRead >= length) {
          break;
        }
      }
    } catch (IOException ioe) {
      throw new TaraException("IOException occurred reading a binary value", ioe);
    }
    return stringBuilder.toString();
  }

  /**
   * Extract a portion of a reader, wrapping the portion in a new reader.
   *
   * @param characterStream The reader for the content
   * @param start The start position/offset (0-based, per general stream/reader contracts).
   * @param length The amount to extract
   * @return The content portion as a reader
   */
  public static Object subStream(Reader characterStream, long start, int length) {
    return new StringReader(extractString(characterStream, start, length));
  }

  /**
   * Extract by bytes from the given stream.
   *
   * @param inputStream The stream of bytes.
   * @return The contents as a {@code byte[]}
   */
  public static byte[] extractBytes(InputStream inputStream) {

    // read the stream contents into a buffer and return the complete byte[]
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(BUFFER_SIZE);
    try {
      byte[] buffer = new byte[BUFFER_SIZE];
      while (true) {
        int amountRead = inputStream.read(buffer);
        if (amountRead == -1) {
          break;
        }
        outputStream.write(buffer, 0, amountRead);
      }
    } catch (IOException ioe) {
      throw new TaraException("IOException occurred reading a binary value", ioe);
    } finally {
      try {
        inputStream.close();
      } catch (IOException e) {
        LOG.error("", e);
      }
      try {
        outputStream.close();
      } catch (IOException e) {
        LOG.error("Close Stream", e);
      }
    }
    return outputStream.toByteArray();
  }

  /**
   * Extract the contents of the given Clob as a string.
   *
   * @param value The clob to to be extracted from
   * @return The content as string
   */
  public static String extractString(final Clob value) {
    try {
      final Reader characterStream = value.getCharacterStream();
      final long length = determineLengthForBufferSizing(value);
      return length > Integer.MAX_VALUE
          ? extractString(characterStream, Integer.MAX_VALUE)
          : extractString(characterStream, (int) length);
    } catch (SQLException e) {
      throw new TaraException("Unable to access lob stream", e);
    }
  }

  /**
   * Determine a buffer size for reading the underlying character stream.
   *
   * @param value The Clob value
   * @return The appropriate buffer size ({@link Clob#length()} by default.
   */
  private static long determineLengthForBufferSizing(Clob value) throws SQLException {
    try {
      return value.length();
    } catch (SQLFeatureNotSupportedException e) {
      return BUFFER_SIZE;
    }
  }

  /**
   * Make sure we allocate a buffer sized not bigger than 2048, not higher than what is actually
   * needed, and at least one.
   *
   * @param lengthHint the expected size of the full value
   * @return the buffer size
   */
  private static int getSuggestedBufferSize(final int lengthHint) {
    return Math.max(1, Math.min(lengthHint, BUFFER_SIZE));
  }
}
