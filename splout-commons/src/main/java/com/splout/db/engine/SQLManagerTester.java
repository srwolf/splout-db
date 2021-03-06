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

import com.splout.db.common.JSONSerDe.JSONSerDeException;
import com.splout.db.engine.EngineManager.EngineException;
import com.splout.db.engine.ResultSerializer.SerializationException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;

/**
 * Generic tester logic for any EngineManager that implements SQL.
 */
public class SQLManagerTester {

  @SuppressWarnings("rawtypes")
  public void basicTest(final EngineManager manager) throws InterruptedException, EngineException, JSONSerDeException, SerializationException {

    manager.query("DROP TABLE IF EXISTS t;", 100);
    manager.query("CREATE TABLE t (a INT, b TEXT);", 100);

    final int nThreads = 10, nOpsPerThread = 100, nWrites = 1000;

    // Insert some foo data
    manager.exec("BEGIN");
    for (int i = 0; i < nWrites; i++) {
      int randInt = (int) (Math.random() * 100000);
      String fooStr = "foo";
      manager.exec("INSERT INTO t (a, b) VALUES (" + randInt + ", \"" + fooStr + "\")");
    }
    manager.exec("COMMIT");

    List results = manager.query("SELECT COUNT(*) FROM t;", 100).mapify();
    assertEquals(((Map) results.get(0)).get("COUNT(*)"), nWrites);

    final AtomicBoolean failed = new AtomicBoolean(false);

    // Read with some parallel threads
    ExecutorService service = Executors.newFixedThreadPool(nThreads);
    for (int i = 0; i < nThreads; i++) {
      service.execute(new Runnable() {
        @Override
        public void run() {
          try {
            for (int i = 0; i < nOpsPerThread; i++) {
              manager.query("SELECT * FROM t;", 2000);
            }
          } catch (Exception e) {
            failed.set(true);
          }
        }
      });
    }
    service.shutdown();
    while (!service.isTerminated()) {
      Thread.sleep(100);
    }

    assertEquals(false, failed.get());

    try {
      manager.query("this will never be a SQL sentence!", 2000);
      throw new AssertionError("Exception was not thrown but it was expected (syntax error)");
    } catch (EngineManager.SyntaxErrorException e) {
    }

    manager.exec("DROP TABLE t;");
  }

  public void querySizeLimitingTest(final EngineManager manager) throws SQLException,
      ClassNotFoundException, JSONSerDeException, EngineException {
    manager.query("DROP TABLE IF EXISTS t;", 100);
    manager.query("CREATE TABLE t (a INT, b TEXT);", 100);

    int nWrites = 100;

    // Insert some foo data
    manager.exec("BEGIN");
    for (int i = 0; i < nWrites; i++) {
      int randInt = (int) (Math.random() * 100000);
      String fooStr = "foo";
      manager.exec("INSERT INTO t (a, b) VALUES (" + randInt + ", \"" + fooStr + "\")");
    }
    manager.exec("COMMIT");

    // Query with hard limit = 99
    try {
      manager.query("SELECT * FROM t;", 99);
      throw new AssertionError("Exception was not thrown but it was expected (query hard limit)");
    } catch (EngineManager.TooManyResultsException e) {
    }

    // Query with hard limit = 10
    try {
      manager.query("SELECT * FROM t;", 10);
      throw new AssertionError("Exception was not thrown but it was expected (query hard limit)");
    } catch (EngineManager.TooManyResultsException e) {
    }

    // Query with hard limit = 1
    try {
      manager.query("SELECT * FROM t;", 1);
      throw new AssertionError("Exception was not thrown but it was expected (query hard limit)");
    } catch (EngineManager.TooManyResultsException e) {
    }
  }




}
