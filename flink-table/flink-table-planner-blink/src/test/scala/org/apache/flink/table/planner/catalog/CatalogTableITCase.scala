/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.catalog

import org.apache.flink.table.api.config.ExecutionConfigOptions
import org.apache.flink.table.api.internal.TableEnvironmentImpl
import org.apache.flink.table.api.{EnvironmentSettings, TableEnvironment, ValidationException}
import org.apache.flink.table.catalog.{CatalogFunctionImpl, GenericInMemoryCatalog, ObjectPath}
import org.apache.flink.table.planner.expressions.utils.Func0
import org.apache.flink.table.planner.factories.utils.TestCollectionTableFactory
import org.apache.flink.table.planner.runtime.utils.JavaUserDefinedScalarFunctions.JavaFunc0
import org.apache.flink.types.Row
import org.junit.Assert.assertEquals
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.{Before, Ignore, Rule, Test}
import java.sql.Timestamp
import java.util

import scala.collection.JavaConversions._

/** Test cases for catalog table. */
@RunWith(classOf[Parameterized])
class CatalogTableITCase(isStreamingMode: Boolean) {
  //~ Instance fields --------------------------------------------------------

  private val settings = if (isStreamingMode) {
    EnvironmentSettings.newInstance().useBlinkPlanner().inStreamingMode().build()
  } else {
    EnvironmentSettings.newInstance().useBlinkPlanner().inBatchMode().build()
  }

  private val tableEnv: TableEnvironment = TableEnvironmentImpl.create(settings)

  var _expectedEx: ExpectedException = ExpectedException.none

  @Rule
  def expectedEx: ExpectedException = _expectedEx

  @Before
  def before(): Unit = {
    tableEnv.getConfig
      .getConfiguration
      .setInteger(ExecutionConfigOptions.TABLE_EXEC_RESOURCE_DEFAULT_PARALLELISM, 1)
    TestCollectionTableFactory.reset()
    TestCollectionTableFactory.isStreaming = isStreamingMode

    val func = new CatalogFunctionImpl(
      classOf[JavaFunc0].getName)
    tableEnv.getCatalog(tableEnv.getCurrentCatalog).get().createFunction(
      new ObjectPath(tableEnv.getCurrentDatabase, "myfunc"),
      func,
      true)
  }

  //~ Tools ------------------------------------------------------------------

  implicit def rowOrdering: Ordering[Row] = Ordering.by((r : Row) => {
    val builder = new StringBuilder
    0 until r.getArity foreach(idx => builder.append(r.getField(idx)))
    builder.toString()
  })

  def toRow(args: Any*):Row = {
    val row = new Row(args.length)
    0 until args.length foreach {
      i => row.setField(i, args(i))
    }
    row
  }

  def execJob(name: String) = {
    tableEnv.execute(name)
  }

  //~ Tests ------------------------------------------------------------------

  private def testUdf(funcPrefix: String): Unit = {
    val sinkDDL =
      """
        |create table sinkT(
        |  a bigint
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(s"insert into sinkT select ${funcPrefix}myfunc(cast(1 as bigint))")
    tableEnv.execute("")
    assertEquals(Seq(toRow(2L)), TestCollectionTableFactory.RESULT.sorted)
  }

  @Test
  def testUdfWithFullIdentifier(): Unit = {
    testUdf("default_catalog.default_database.")
  }

  @Test
  def testUdfWithDatabase(): Unit = {
    testUdf("default_database.")
  }

  @Test
  def testUdfWithNon(): Unit = {
    testUdf("")
  }

  @Test(expected = classOf[ValidationException])
  def testUdfWithWrongCatalog(): Unit = {
    testUdf("wrong_catalog.default_database.")
  }

  @Test(expected = classOf[ValidationException])
  def testUdfWithWrongDatabase(): Unit = {
    testUdf("default_catalog.wrong_database.")
  }

  @Test
  def testInsertInto(): Unit = {
    val sourceData = List(
      toRow(1, "1000", 2),
      toRow(2, "1", 3),
      toRow(3, "2000", 4),
      toRow(1, "2", 2),
      toRow(2, "3000", 3)
    )
    TestCollectionTableFactory.initData(sourceData)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b varchar,
        |  c int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b varchar,
        |  c int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select t1.a, t1.b, (t1.a + 1) as c from t1
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(sourceData.sorted, TestCollectionTableFactory.RESULT.sorted)
  }

  @Test
  def testInsertSourceTableExpressionFields(): Unit = {
    val sourceData = List(
      toRow(1, "1000"),
      toRow(2, "1"),
      toRow(3, "2000"),
      toRow(1, "2"),
      toRow(2, "3000")
    )
    val expected = List(
      toRow(1, "1000", 2),
      toRow(2, "1", 3),
      toRow(3, "2000", 4),
      toRow(1, "2", 2),
      toRow(2, "3000", 3)
    )
    TestCollectionTableFactory.initData(sourceData)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b varchar,
        |  c as a + 1
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b varchar,
        |  c int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select t1.a, t1.b, t1.c from t1
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(expected.sorted, TestCollectionTableFactory.RESULT.sorted)
  }

  // Test the computation expression in front of referenced columns.
  @Test
  def testInsertSourceTableExpressionFieldsBeforeReferences(): Unit = {
    val sourceData = List(
      toRow(1, "1000"),
      toRow(2, "1"),
      toRow(3, "2000"),
      toRow(2, "2"),
      toRow(2, "3000")
    )
    val expected = List(
      toRow(101, 1, "1000"),
      toRow(102, 2, "1"),
      toRow(103, 3, "2000"),
      toRow(102, 2, "2"),
      toRow(102, 2, "3000")
    )
    TestCollectionTableFactory.initData(sourceData)
    val sourceDDL =
      """
        |create table t1(
        |  c as a + 100,
        |  a int,
        |  b varchar
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  c int,
        |  a int,
        |  b varchar
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select t1.c, t1.a, t1.b from t1
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(expected.sorted, TestCollectionTableFactory.RESULT.sorted)
  }

  @Test
  def testInsertSourceTableWithFuncField(): Unit = {
    val sourceData = List(
      toRow(1, "1990-02-10 12:34:56"),
      toRow(2, "2019-09-10 9:23:41"),
      toRow(3, "2019-09-10 9:23:42"),
      toRow(1, "2019-09-10 9:23:43"),
      toRow(2, "2019-09-10 9:23:44")
    )
    val expected = List(
      toRow(1, "1990-02-10 12:34:56", Timestamp.valueOf("1990-02-10 12:34:56")),
      toRow(2, "2019-09-10 9:23:41", Timestamp.valueOf("2019-09-10 9:23:41")),
      toRow(3, "2019-09-10 9:23:42", Timestamp.valueOf("2019-09-10 9:23:42")),
      toRow(1, "2019-09-10 9:23:43", Timestamp.valueOf("2019-09-10 9:23:43")),
      toRow(2, "2019-09-10 9:23:44", Timestamp.valueOf("2019-09-10 9:23:44"))
    )
    TestCollectionTableFactory.initData(sourceData)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b varchar,
        |  c as to_timestamp(b)
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b varchar,
        |  c timestamp
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select t1.a, t1.b, t1.c from t1
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(expected.sorted, TestCollectionTableFactory.RESULT.sorted)
  }

  @Test
  def testInsertSourceTableWithUserDefinedFuncField(): Unit = {
    val sourceData = List(
      toRow(1, "1990-02-10 12:34:56"),
      toRow(2, "2019-09-10 9:23:41"),
      toRow(3, "2019-09-10 9:23:42"),
      toRow(1, "2019-09-10 9:23:43"),
      toRow(2, "2019-09-10 9:23:44")
    )
    val expected = List(
      toRow(1, "1990-02-10 12:34:56", 1),
      toRow(2, "2019-09-10 9:23:41", 2),
      toRow(3, "2019-09-10 9:23:42", 3),
      toRow(1, "2019-09-10 9:23:43", 1),
      toRow(2, "2019-09-10 9:23:44", 2)
    )
    TestCollectionTableFactory.initData(sourceData)
    tableEnv.registerFunction("my_udf", Func0)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b varchar,
        |  c as my_udf(a)
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b varchar,
        |  c int not null
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select t1.a, t1.b, t1.c from t1
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(expected.sorted, TestCollectionTableFactory.RESULT.sorted)
  }

  @Test
  def testInsertSinkTableExpressionFields(): Unit = {
    val sourceData = List(
      toRow(1, "1000"),
      toRow(2, "1"),
      toRow(3, "2000"),
      toRow(1, "2"),
      toRow(2, "3000")
    )
    val expected = List(
      toRow(1, 2),
      toRow(1, 2),
      toRow(2, 3),
      toRow(2, 3),
      toRow(3, 4)
    )
    TestCollectionTableFactory.initData(sourceData)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b varchar,
        |  c as a + 1
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b as c - 1,
        |  c int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select t1.a, t1.c from t1
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(expected.sorted, TestCollectionTableFactory.RESULT.sorted)
  }

  @Test
  def testInsertSinkTableWithUnmatchedFields(): Unit = {
    val sourceData = List(
      toRow(1, "1000"),
      toRow(2, "1"),
      toRow(3, "2000"),
      toRow(1, "2"),
      toRow(2, "3000")
    )
    TestCollectionTableFactory.initData(sourceData)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b varchar,
        |  c as a + 1
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b as cast(a as varchar(20)) || cast(c as varchar(20)),
        |  c int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select t1.a, t1.b from t1
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    expectedEx.expect(classOf[ValidationException])
    expectedEx.expectMessage("Field types of query result and registered TableSink "
      + "`default_catalog`.`default_database`.`t2` do not match.")
    execJob("testJob")
  }

  @Test
  def testInsertWithJoinedSource(): Unit = {
    val sourceData = List(
      toRow(1, 1000, 2),
      toRow(2, 1, 3),
      toRow(3, 2000, 4),
      toRow(1, 2, 2),
      toRow(2, 3000, 3)
    )

    val expected = List(
      toRow(1, 1000, 2, 1),
      toRow(1, 2, 2, 1),
      toRow(2, 1, 1, 2),
      toRow(2, 3000, 1, 2)
    )
    TestCollectionTableFactory.initData(sourceData)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b int,
        |  c int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b int,
        |  c int,
        |  d int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select a.a, a.b, b.a, b.b
        |  from t1 a
        |  join t1 b
        |  on a.a = b.b
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(expected.sorted, TestCollectionTableFactory.RESULT.sorted)
  }

  @Test
  def testInsertWithAggregateSource(): Unit = {
    if (isStreamingMode) {
      return
    }
    val sourceData = List(
      toRow(1, 1000, 2),
      toRow(2, 1000, 3),
      toRow(3, 2000, 4),
      toRow(4, 2000, 5),
      toRow(5, 3000, 6)
    )

    val expected = List(
      toRow(3, 1000),
      toRow(5, 3000),
      toRow(7, 2000)
    )
    TestCollectionTableFactory.initData(sourceData)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b int,
        |  c int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select sum(a), t1.b from t1 group by t1.b
      """.stripMargin
    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(expected.sorted, TestCollectionTableFactory.RESULT.sorted)
  }

  @Test @Ignore // need to implement
  def testStreamSourceTableWithProctime(): Unit = {
    val sourceData = List(
      toRow(1, 1000),
      toRow(2, 2000),
      toRow(3, 3000)
    )
    TestCollectionTableFactory.initData(sourceData, emitInterval = 1000L)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b int,
        |  c as proctime,
        |  primary key(a)
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select sum(a), sum(b) from t1 group by TUMBLE(c, INTERVAL '1' SECOND)
      """.stripMargin

    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(TestCollectionTableFactory.RESULT.sorted, sourceData.sorted)
  }

  @Test @Ignore("FLINK-14320") // need to implement
  def testStreamSourceTableWithRowtime(): Unit = {
    val sourceData = List(
      toRow(1, 1000),
      toRow(2, 2000),
      toRow(3, 3000)
    )
    TestCollectionTableFactory.initData(sourceData, emitInterval = 1000L)
    val sourceDDL =
      """
        |create table t1(
        |  a timestamp(3),
        |  b bigint,
        |  WATERMARK FOR a AS a - interval '1' SECOND
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a timestamp(3),
        |  b bigint
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select a, sum(b) from t1 group by TUMBLE(a, INTERVAL '1' SECOND)
      """.stripMargin

    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(TestCollectionTableFactory.RESULT.sorted, sourceData.sorted)
  }

  @Test @Ignore // need to implement
  def testBatchSourceTableWithProctime(): Unit = {
    val sourceData = List(
      toRow(1, 1000),
      toRow(2, 2000),
      toRow(3, 3000)
    )
    TestCollectionTableFactory.initData(sourceData, emitInterval = 1000L)
    val sourceDDL =
      """
        |create table t1(
        |  a int,
        |  b int,
        |  c as proctime,
        |  primary key(a)
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a int,
        |  b int
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select sum(a), sum(b) from t1 group by TUMBLE(c, INTERVAL '1' SECOND)
      """.stripMargin

    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(TestCollectionTableFactory.RESULT.sorted, sourceData.sorted)
  }

  @Test @Ignore("FLINK-14320") // need to implement
  def testBatchTableWithRowtime(): Unit = {
    val sourceData = List(
      toRow(1, 1000),
      toRow(2, 2000),
      toRow(3, 3000)
    )
    TestCollectionTableFactory.initData(sourceData, emitInterval = 1000L)
    val sourceDDL =
      """
        |create table t1(
        |  a timestamp(3),
        |  b bigint,
        |  WATERMARK FOR a AS a - interval '1' SECOND
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val sinkDDL =
      """
        |create table t2(
        |  a timestamp(3),
        |  b bigint
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val query =
      """
        |insert into t2
        |select a, sum(b) from t1 group by TUMBLE(a, INTERVAL '1' SECOND)
      """.stripMargin

    tableEnv.sqlUpdate(sourceDDL)
    tableEnv.sqlUpdate(sinkDDL)
    tableEnv.sqlUpdate(query)
    execJob("testJob")
    assertEquals(TestCollectionTableFactory.RESULT.sorted, sourceData.sorted)
  }

  @Test
  def testDropTableWithFullPath(): Unit = {
    val ddl1 =
      """
        |create table t1(
        |  a bigint,
        |  b bigint,
        |  c varchar
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val ddl2 =
      """
        |create table t2(
        |  a bigint,
        |  b bigint
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin

    tableEnv.sqlUpdate(ddl1)
    tableEnv.sqlUpdate(ddl2)
    assert(tableEnv.listTables().sameElements(Array[String]("t1", "t2")))
    tableEnv.sqlUpdate("DROP TABLE default_catalog.default_database.t2")
    assert(tableEnv.listTables().sameElements(Array("t1")))
  }

  @Test
  def testDropTableWithPartialPath(): Unit = {
    val ddl1 =
      """
        |create table t1(
        |  a bigint,
        |  b bigint,
        |  c varchar
        |) with (
        | 'connector' = 'COLLECTION'
        |)
      """.stripMargin
    val ddl2 =
      """
        |create table t2(
        |  a bigint,
        |  b bigint
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin

    tableEnv.sqlUpdate(ddl1)
    tableEnv.sqlUpdate(ddl2)
    assert(tableEnv.listTables().sameElements(Array[String]("t1", "t2")))
    tableEnv.sqlUpdate("DROP TABLE default_database.t2")
    tableEnv.sqlUpdate("DROP TABLE t1")
    assert(tableEnv.listTables().isEmpty)
  }

  @Test(expected = classOf[ValidationException])
  def testDropTableWithInvalidPath(): Unit = {
    val ddl1 =
      """
        |create table t1(
        |  a bigint,
        |  b bigint,
        |  c varchar
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin

    tableEnv.sqlUpdate(ddl1)
    assert(tableEnv.listTables().sameElements(Array[String]("t1")))
    tableEnv.sqlUpdate("DROP TABLE catalog1.database1.t1")
  }

  @Test
  def testDropTableWithInvalidPathIfExists(): Unit = {
    val ddl1 =
      """
        |create table t1(
        |  a bigint,
        |  b bigint,
        |  c varchar
        |) with (
        |  'connector' = 'COLLECTION'
        |)
      """.stripMargin

    tableEnv.sqlUpdate(ddl1)
    assert(tableEnv.listTables().sameElements(Array[String]("t1")))
    tableEnv.sqlUpdate("DROP TABLE IF EXISTS catalog1.database1.t1")
    assert(tableEnv.listTables().sameElements(Array[String]("t1")))
  }

  @Test
  def testUseCatalog(): Unit = {
    tableEnv.registerCatalog("cat1", new GenericInMemoryCatalog("cat1"))
    tableEnv.registerCatalog("cat2", new GenericInMemoryCatalog("cat2"))
    tableEnv.sqlUpdate("use catalog cat1")
    assertEquals("cat1", tableEnv.getCurrentCatalog)
    tableEnv.sqlUpdate("use catalog cat2")
    assertEquals("cat2", tableEnv.getCurrentCatalog)
  }
}

object CatalogTableITCase {
  @Parameterized.Parameters(name = "{0}")
  def parameters(): java.util.Collection[Boolean] = {
    util.Arrays.asList(true, false)
  }
}