/*
 * Copyright (C) 2017 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.julienviet.pgclient.impl;

import com.julienviet.pgclient.PgBatchResult;
import com.julienviet.pgclient.PgResult;
import com.julienviet.pgclient.Row;
import com.julienviet.pgclient.Tuple;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;

class BatchQueryResultHandler implements QueryResultHandler<Row> {

  private final Handler<AsyncResult<PgBatchResult<Row>>> handler;
  private PgBatchResultImpl<Row> list;
  private Throwable failure;

  public BatchQueryResultHandler(int size, Handler<AsyncResult<PgBatchResult<Row>>> handler) {
    this.handler = handler;
    this.list = new PgBatchResultImpl<>(size);
  }

  @Override
  public void handleResult(PgResult<Row> result, boolean suspended) {
    list.add(result);
  }

  @Override
  public void handleFailure(Throwable cause) {
    failure = cause;
  }

  @Override
  public void handleEnd() {
    if (failure != null) {
      handler.handle(Future.failedFuture(failure));
    } else {
      handler.handle(Future.succeededFuture(list));
    }
  }
}
