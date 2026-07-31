
package com.sondertara.joya.migration.features;

public abstract class AbstractProductFeatures implements ProductFeatures {

  @Override
  public int convertFetchSize(int fetchSize) {
    return fetchSize;
  }
}
