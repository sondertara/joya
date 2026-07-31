
package com.sondertara.joya.migration.provider.transform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Schema/Table/Column三元组定义
 */
@Data
@Builder
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class SchemaTableColumnTuple {

  private String schema;
  private String table;
  private String column;
}
