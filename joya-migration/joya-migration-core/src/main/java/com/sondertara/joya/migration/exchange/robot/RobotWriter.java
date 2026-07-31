
package com.sondertara.joya.migration.exchange.robot;


import com.sondertara.joya.migration.exchange.task.TaskResult;

public abstract class RobotWriter<R extends TaskResult> extends AbstractRobot<R> {


  public abstract void waitForFinish();
}
