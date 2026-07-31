
package com.sondertara.joya.migration.exchange.robot;



import com.sondertara.joya.migration.exchange.MemChannel;
import com.sondertara.joya.migration.exchange.task.TaskResult;

import java.util.Optional;

public abstract class AbstractRobot<R extends TaskResult> implements Robot {

  private MemChannel channel;

  public void setChannel(MemChannel channel) {
    this.channel = channel;
  }

  public MemChannel getChannel() {
    return this.channel;
  }

  public void clearChannel() {
    this.channel.clear();
  }

  public abstract Optional<R> getWorkResult();
}
