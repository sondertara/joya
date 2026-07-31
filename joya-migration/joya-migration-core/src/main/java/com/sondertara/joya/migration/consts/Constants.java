
package com.sondertara.joya.migration.consts;

/**
 * 常量值定义
 *
 * @author tang
 */
public final class Constants {

  /**
   * What's the file systems file separator on this operating system?
   */
  public static final String FILE_SEPARATOR = System.getProperty("file.separator");

  /**
   * What's the path separator on this operating system?
   */
  public static final String PATH_SEPARATOR = System.getProperty("path.separator");

  /**
   * CR: operating systems specific Carriage Return
   */
  public static final String CR = System.getProperty("line.separator");

  /**
   * DOSCR: MS-DOS specific Carriage Return
   */
  public static final String DOSCR = "\n\r";

  /**
   * An empty ("") String.
   */
  public static final String EMPTY_STRING = "";

  /**
   * The Java runtime version
   */
  public static final String JAVA_VERSION = System.getProperty("java.vm.version");

  /**
   * Create Table Statement Prefix String
   */
  public static final String CREATE_TABLE = "CREATE TABLE ";

  /**
   * Drop Table Statement Prefix String
   */
  public static final String DROP_TABLE = "DROP TABLE ";

  /**
   * Constant Keyword String
   */
  public static final String IF_NOT_EXISTS = "IF NOT EXISTS ";

  /**
   * Constant Keyword String
   */
  public static final String IF_EXISTS = "IF EXISTS ";

  public static final int CLOB_LENGTH = 9999999;

  /**
   * Default jdbc query timeout seconds
   */
  public static Integer DEFAULT_QUERY_TIMEOUT_SECONDS = 1 * 60 * 60;

  /**
   * default jdbc fetch-size value
   */
  public static int DEFAULT_FETCH_SIZE = 1000;

  /**
   * minimum jdbc fetch-size value
   */
  public static int MINIMUM_FETCH_SIZE = 100;

  /**
   * database product module SPI file path name
   */
  public static final String SPI_FILE = "META-INF/services/dbswitch.providers";

  /**
   * for distributed database,like greenplum
   */
  public static final String DISTRIBUTED_KEY = "distributed_key_";
}
