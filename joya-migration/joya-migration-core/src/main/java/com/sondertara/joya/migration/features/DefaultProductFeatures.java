
package com.sondertara.joya.migration.features;

public class DefaultProductFeatures implements ProductFeatures {

  @Override
  public int convertFetchSize(int fetchSize) {
    return fetchSize;
  }
}
