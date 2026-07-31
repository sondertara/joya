
package com.sondertara.joya.migration.exchange;


import com.sondertara.common.base.Assert;
import com.sondertara.joya.migration.exchange.robot.RobotReader;
import com.sondertara.joya.migration.exchange.robot.RobotWriter;

import java.util.concurrent.Executor;

public abstract class AbstractBatchExchanger {

  private MemChannel memChannel;
  private Executor readThreadExecutor;
  private Executor writeThreadExecutor;

  public AbstractBatchExchanger(Executor readExecutor, Executor writeExecutor, int channelMaxSize) {
    Assert.notNull(readExecutor, "readExecutor is null");
    Assert.notNull(writeExecutor, "writeExecutor is null");
    this.memChannel = MemChannel.createNewChannel(channelMaxSize);
    this.readThreadExecutor = readExecutor;
    this.writeThreadExecutor = writeExecutor;
  }

  public MemChannel getMemChannel() {
    return memChannel;
  }

  public int getChannelWaitingNum() {
    return memChannel.size();
  }

  public void exchange(RobotReader reader, RobotWriter writer) {
    // 为reader和writer配置数据传输隧道
    reader.setChannel(this.memChannel);
    writer.setChannel(this.memChannel);

    // 初始化reader和writer
    reader.init(readThreadExecutor);
    writer.init(writeThreadExecutor);

    // 启动reader和writer的并行工作
    writer.startup();
    reader.startup();

    // writer会等待reader执行完
    writer.waitForFinish();

    // 收集统计信息
    Throwable throwable = collectPerfStats(reader, writer);
    if (null != throwable) {
      if (throwable instanceof RuntimeException) {
        throw (RuntimeException) throwable;
      }
      throw new RuntimeException(throwable);
    }
  }

  protected abstract Throwable collectPerfStats(RobotReader reader, RobotWriter writer);
}
