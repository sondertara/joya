
package com.sondertara.joya.migration.exchange.robot;

import com.sondertara.joya.migration.Lifecycle;

import java.util.concurrent.Executor;

public interface Robot extends Lifecycle<Executor> {



  void interrupt();
}
