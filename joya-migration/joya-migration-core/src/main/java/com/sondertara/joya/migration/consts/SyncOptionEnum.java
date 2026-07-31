
package com.sondertara.joya.migration.consts;

/**
 * 同步操作类型的枚举类
 *
 * @author tang
 */
public enum SyncOptionEnum {

  ONLY_INSERT(1),
  ONLY_UPDATE(2),
  INSERT_UPDATE(3),
  ONLY_DELETE(4),
  INSERT_DELETE(5),
  UPDATE_DELETE(6),
  INSERT_UPDATE_DELETE(7),
  ;

  private static int MASK_INSERT = 1;
  private static int MASK_UPDATE = 2;
  private static int MASK_DELETE = 4;

  private int flag;

  SyncOptionEnum(int flat) {
    this.flag = flat;
  }

  public boolean callInsert() {
    return (flag & MASK_INSERT) != 0;
  }

  public boolean callUpdate() {
    return (flag & MASK_UPDATE) != 0;
  }

  public boolean callDelete() {
    return (flag & MASK_DELETE) != 0;
  }

  public static void main(String[] args) {
    for (SyncOptionEnum optionEnum : values()) {
      System.out.println(optionEnum.name() + ":"
          + String.format("insert(%s),update(%s),delete(%s)",
          optionEnum.callInsert(), optionEnum.callUpdate(), optionEnum.callDelete()));
    }
  }
}
