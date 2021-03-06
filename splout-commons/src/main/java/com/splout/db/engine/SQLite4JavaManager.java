package com.splout.db.engine;

/*
 * #%L
 * Splout SQL commons
 * %%
 * Copyright (C) 2012 Datasalt Systems S.L.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.almworks.sqlite4java.SQLiteConstants;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteInterruptedException;
import com.splout.db.common.QueryResult;
import com.splout.db.common.TimeoutThread;
import org.apache.commons.configuration.Configuration;

import java.io.File;
import java.util.List;

/**
 *
 */
public class SQLite4JavaManager implements EngineManager {

  private SQLite4JavaClient client;

  public SQLite4JavaManager() {

  }

  SQLite4JavaManager(String dbFile, List<String> initStatements) {
    this.client = new SQLite4JavaClient(dbFile, initStatements);
  }

  public void setTimeoutThread(TimeoutThread t) {
    client.setTimeoutThread(t);
  }

  @Override
  public void init(File dbFile, Configuration config, List<String> initStatements) throws EngineException {
    this.client = new SQLite4JavaClient(dbFile + "", initStatements);
  }

  @Override
  public QueryResult exec(String query) throws EngineException {
    try {
      return client.exec(query);
    } catch (SQLiteException e) {
      throw convertException(e);
    }
  }

  @Override
  public QueryResult query(String query, int maxResults) throws EngineException {
    try {
      return client.query(query, maxResults);
    } catch (SQLiteException e) {
      throw convertException(e);
    }
  }

  /**
   * For unit-testing
   */
  SQLite4JavaClient getClient() {
    return client;
  }

  @Override
  public void close() {
    client.close();
  }

  @Override
  public void streamQuery(StreamingIterator visitor) throws EngineException {
    try {
      client.stream(visitor);
    } catch (SQLiteException e) {
      throw convertException(e);
    }
  }

  /**
   * Converts SQLiteExceptions into the corresponding
   * EngineException.
   */
  protected static EngineException convertException(SQLiteException e) {
    if (e instanceof SQLiteInterruptedException) {
      return new QueryInterruptedException(e.getMessage());
    } else {
      // Normal db errors: syntax error, database not set errors, etc.
      // This kind of errors should not retry in replicas.
      if (e.getErrorCode() == SQLiteConstants.SQLITE_ERROR) {
        String message = e.getMessage();
        if (message != null && message.contains("syntax error]")) {
          return new SyntaxErrorException(e.getMessage());
        } else {
          return new ShouldNotRetryInReplicaException(e.getMessage());
        }
      } if (e.getErrorCode() == SQLite4JavaClient.ERROR_CODE_MAXIMUM_RESULTS_REACHED) {
        return new TooManyResultsException(e.getMessage());
      } else {
        // Unexpected exception. Should rety in replica.
        return new ShouldRetryInReplicaException(e.getMessage(), e);
      }
    }
  }
}
