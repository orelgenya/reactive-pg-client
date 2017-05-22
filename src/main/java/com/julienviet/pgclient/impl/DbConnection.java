package com.julienviet.pgclient.impl;


import com.julienviet.pgclient.PostgresConnection;
import com.julienviet.pgclient.Result;
import com.julienviet.pgclient.Row;
import com.julienviet.pgclient.codec.Column;
import com.julienviet.pgclient.codec.DataFormat;
import com.julienviet.pgclient.codec.DataType;
import com.julienviet.pgclient.codec.Message;
import com.julienviet.pgclient.codec.TransactionStatus;
import com.julienviet.pgclient.codec.decoder.message.AuthenticationClearTextPassword;
import com.julienviet.pgclient.codec.decoder.message.AuthenticationMD5Password;
import com.julienviet.pgclient.codec.decoder.message.AuthenticationOk;
import com.julienviet.pgclient.codec.decoder.message.BackendKeyData;
import com.julienviet.pgclient.codec.decoder.message.BindComplete;
import com.julienviet.pgclient.codec.decoder.message.CloseComplete;
import com.julienviet.pgclient.codec.decoder.message.CommandComplete;
import com.julienviet.pgclient.codec.decoder.message.DataRow;
import com.julienviet.pgclient.codec.decoder.message.EmptyQueryResponse;
import com.julienviet.pgclient.codec.decoder.message.ErrorResponse;
import com.julienviet.pgclient.codec.decoder.message.NoData;
import com.julienviet.pgclient.codec.decoder.message.NotificationResponse;
import com.julienviet.pgclient.codec.decoder.message.ParameterDescription;
import com.julienviet.pgclient.codec.decoder.message.ParameterStatus;
import com.julienviet.pgclient.codec.decoder.message.ParseComplete;
import com.julienviet.pgclient.codec.decoder.message.PortalSuspended;
import com.julienviet.pgclient.codec.decoder.message.ReadyForQuery;
import com.julienviet.pgclient.codec.decoder.message.RowDescription;
import com.julienviet.pgclient.codec.encoder.message.Bind;
import com.julienviet.pgclient.codec.encoder.message.Close;
import com.julienviet.pgclient.codec.encoder.message.Describe;
import com.julienviet.pgclient.codec.encoder.message.Execute;
import com.julienviet.pgclient.codec.encoder.message.Parse;
import com.julienviet.pgclient.codec.encoder.message.PasswordMessage;
import com.julienviet.pgclient.codec.encoder.message.Query;
import com.julienviet.pgclient.codec.encoder.message.StartupMessage;
import com.julienviet.pgclient.codec.encoder.message.Sync;
import com.julienviet.pgclient.codec.encoder.message.Terminate;
import com.julienviet.pgclient.codec.formatter.DateTimeFormatter;
import com.julienviet.pgclient.codec.formatter.TimeFormatter;
import com.julienviet.pgclient.codec.util.Util;
import io.netty.channel.Channel;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.metrics.impl.DummyVertxMetrics;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.core.spi.metrics.NetworkMetrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayDeque;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.*;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class DbConnection extends ConnectionBase {

  enum Status {

    CLOSED, CONNECTED, CLOSING

  }

  private final ArrayDeque<Command> inflight = new ArrayDeque<>();
  private final ArrayDeque<Command> pending = new ArrayDeque<>();
  final PostgresClientImpl client;
  private RowDescription rowDesc;
  private Result result;
  private Status status = Status.CONNECTED;
  private final String UTF8 = "UTF8";
  private String CLIENT_ENCODING;

  public DbConnection(PostgresClientImpl client, VertxInternal vertx, Channel channel, ContextImpl context) {
    super(vertx, channel, context);

    this.client = client;
  }

  final PostgresConnection conn = new PostgresConnectionImpl(this);

  void init(String username, String database, Handler<AsyncResult<DbConnection>> completionHandler) {
    execute(new StartupCommand(username, database, completionHandler));
  }

  void doClose() {
    if (Vertx.currentContext() == context) {
      if (status == Status.CONNECTED) {
        status = Status.CLOSING;
        writeToChannel(Terminate.INSTANCE);
      }
    } else {
      context.runOnContext(v -> doClose());
    }
  }

  void schedule(Command cmd) {
    if (Vertx.currentContext() == context) {
      if (status == Status.CONNECTED) {
        if (inflight.size() < client.pipeliningLimit) {
          execute(cmd);
        } else {
          pending.add(cmd);
        }
      } else {
        cmd.onError("Connection not open " + status);
      }
    } else {
      context.runOnContext(v -> schedule(cmd));
    }
  }

  void execute(Command cmd) {
    if(cmd.getClass() == QueryCommand.class) {
      executeQuery((QueryCommand) cmd);
    } else if (cmd instanceof BatchExecuteCommand) {
      executeBindDescribeExecute((BatchExecuteCommand) cmd);
    } else if (cmd instanceof CloseStatementCommand) {
      executeCloseStatement((CloseStatementCommand) cmd);
    } else if (cmd instanceof StartupCommand) {
      executeStartup((StartupCommand) cmd);
    }
  }

  private void handleText(DataType type, byte[] data, Row row) {
    if(data == null) {
      row.add(null);
      return;
    }
    if(type == DataType.CHAR) {
      row.add((char) data[0]);
      return;
    }
    if(type == DataType.BOOL) {
      if(data[0] == 't') {
        row.add(true);
      } else {
        row.add(false);
      }
      return;
    }
    String value = new String(data, UTF_8);
    switch (type) {
      case INT2:
        row.add(Short.parseShort(value));
        break;
      case INT4:
        row.add(Integer.parseInt(value));
        break;
      case INT8:
        row.add(Long.parseLong(value));
        break;
      case FLOAT4:
        row.add(Float.parseFloat(value));
        break;
      case FLOAT8:
        row.add(Double.parseDouble(value));
        break;
      case NUMERIC:
        row.add(new BigDecimal(value));
        break;
      case BPCHAR:
      case VARCHAR:
      case NAME:
      case TEXT:
        row.add(value);
        break;
      case UUID:
        row.add(java.util.UUID.fromString(value));
        break;
      case DATE:
        row.add(LocalDate.parse(value));
        break;
      case TIME:
        row.add(LocalTime.parse(value));
        break;
      case TIMETZ:
        row.add(OffsetTime.parse(value, TimeFormatter.TIMETZ_FORMAT));
        break;
      case TIMESTAMP:
        row.add(LocalDateTime.parse(value, DateTimeFormatter.TIMESTAMP_FORMAT));
        break;
      case TIMESTAMPTZ:
        row.add(OffsetDateTime.parse(value, DateTimeFormatter.TIMESTAMPTZ_FORMAT));
        break;
      case JSON:
      case JSONB:
        if(value.charAt(0)== '{') {
          row.add(new JsonObject(value));
        } else {
          row.add(new JsonArray(value));
        }
        break;
      default:
        System.out.println("unsupported " + type);
        break;
    }
  }

  private void handleBinary(DataType type, byte[] data, Row row) {

  }

  void handleMessage(Message msg) {
    if (msg.getClass() == AuthenticationMD5Password.class) {
      AuthenticationMD5Password authMD5 = (AuthenticationMD5Password) msg;
      writeToChannel(new PasswordMessage(client.username, client.password, authMD5.getSalt()));
    } else if (msg.getClass() == AuthenticationClearTextPassword.class) {
      writeToChannel(new PasswordMessage(client.username, client.password, null));
    } else if (msg.getClass() == AuthenticationOk.class) {
//      handler.handle(Future.succeededFuture(conn));
//      handler = null;
    } else if (msg.getClass() == ReadyForQuery.class) {
      // Ready for query
      TransactionStatus status = ((ReadyForQuery) msg).getTransactionStatus();
      Command cmd = pending.poll();
      if (cmd != null) {
        execute(cmd);
      }
    } else if (msg.getClass() == ParseComplete.class) {

    } else if (msg.getClass() == BindComplete.class) {

    } else if (msg.getClass() == CloseComplete.class) {
      inflight.poll().onSuccess(null);
    } else if (msg.getClass() == EmptyQueryResponse.class) {

    } else if (msg.getClass() == ParameterDescription.class) {

    } else if (msg.getClass() == BackendKeyData.class) {
      // The final phase before returning the connection
      // We should make sure we are supporting only UTF8
      // https://www.postgresql.org/docs/9.5/static/multibyte.html#MULTIBYTE-CHARSET-SUPPORTED
      if(!CLIENT_ENCODING.equals(UTF8)) {
        inflight.poll().onError(CLIENT_ENCODING + " is not supported in the client only " + UTF8);
      } else {
        inflight.poll().onSuccess(this);
      }
    } else if (msg.getClass() == NotificationResponse.class) {

    } else if (msg.getClass() == ParameterStatus.class) {
      ParameterStatus paramStatus = (ParameterStatus) msg;
      if(paramStatus.getKey().equals("client_encoding")) {
        CLIENT_ENCODING = paramStatus.getValue();
      }
    } else if (msg.getClass() == PortalSuspended.class) {
      // if an Execute message's rowsLimit was reached
    } else if (msg.getClass() == NoData.class) {

    } else if (msg.getClass() == RowDescription.class) {
      rowDesc = (RowDescription) msg;
      result = new Result();
    } else if (msg.getClass() == DataRow.class) {
      DataRow dataRow = (DataRow) msg;
      Column[] columns = rowDesc.getColumns();
      Row row = new Row();
      for (int i = 0; i < columns.length; i++) {
        Column columnDesc = columns[i];
        DataFormat dataFormat = columnDesc.getDataFormat();
        DataType dataType = columnDesc.getDataType();
        byte[] data = dataRow.getValue(i);
        switch (dataFormat) {
          case TEXT: {
            handleText(dataType, data, row);
          }
          break;
          case BINARY: {
            handleBinary(dataType, data, row);
          }
          break;
        }
      }
      result.add(row);
    } else if (msg.getClass() == CommandComplete.class) {
      CommandComplete complete = (CommandComplete) msg;
      Result r = result;
      result = null;
      rowDesc = null;
      if (r == null) {
        r = new Result();
      }
      r.setUpdatedRows(complete.getRowsAffected());
      inflight.poll().onSuccess(r);
    } else if (msg.getClass() == ErrorResponse.class) {
      ErrorResponse error = (ErrorResponse) msg;
/*
      if (handler != null) {
        handler.handle(Future.failedFuture(error.getMessage()));
        handler = null;
        close();
        return;
      }
*/
      result = null;
      rowDesc = null;
      inflight.poll().onError(error.getMessage());
    } else {
      System.out.println("Unhandled message " + msg);
    }
  }

  @Override
  protected void handleClosed() {
    status = Status.CLOSED;
    for (ArrayDeque<Command> q : Arrays.asList(inflight, pending)) {
      Command cmd;
      while ((cmd = q.poll()) != null) {
        Command c = cmd;
        context.runOnContext(v -> c.onError("closed"));
      }
    }
    super.handleClosed();
  }

  @Override
  protected synchronized void handleException(Throwable t) {
    super.handleException(t);
    close();
  }

  void executeQuery(QueryCommand cmd) {
    inflight.add(cmd);
    writeToChannel(new Query(cmd.getSql()));
  }

  void executeBindDescribeExecute(BatchExecuteCommand cmd) {
    inflight.add(cmd);
    PreparedStatementImpl ps = cmd.ps;
    if (!ps.parsed) {
      ps.parsed = true;
      writeToChannel(new Parse(ps.sql).setStatement(ps.stmt));
    }
    writeToChannel(new Bind(Util.paramValues(cmd.params)).setStatement(ps.stmt));
    writeToChannel(new Describe().setStatement(ps.stmt));
    writeToChannel(new Execute().setRowCount(0));
    if (cmd.sync) {
      writeToChannel(Sync.INSTANCE);
    }
  }

  void executeCloseStatement(CloseStatementCommand cmd) {
    PreparedStatementImpl ps = cmd.ps;
    if (ps.parsed) {
      inflight.add(cmd);
      writeToChannel(new Close().setStatement(ps.stmt));
      writeToChannel(Sync.INSTANCE);
    } else {
      cmd.onSuccess(null);
    }
  }

  void executeStartup(StartupCommand cmd) {
    inflight.add(cmd);
    writeToChannel(new StartupMessage(cmd.username, cmd.database));
  }

  @Override
  public NetworkMetrics metrics() {
    return new DummyVertxMetrics.DummyDatagramMetrics();
  }

  @Override
  protected void handleInterestedOpsChanged() {

  }

}
