
package com.sondertara.joya.migration.exchange.robot;


import com.sondertara.joya.migration.exchange.task.TaskResult;

public abstract class RobotReader<R extends TaskResult> extends AbstractRobot<R> {

  public abstract long getRemainingCount();
}
